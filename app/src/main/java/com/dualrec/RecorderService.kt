package com.dualrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** VIDEO phone: records the screen (no audio), then pulls the audio phone's recording over Bluetooth and merges. */
class RecorderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var proj: MediaProjection? = null
    private var mr: MediaRecorder? = null
    private var vd: VirtualDisplay? = null
    private var starter: Thread? = null
    private val stopping = AtomicBoolean(false)
    @Volatile private var bStarted = false
    private var offsetUs = 0L
    private var mac = ""
    private var nudge = 0L
    private val video by lazy { File(cacheDir, "screen.mp4") }
    private val audio by lazy { File(cacheDir, "audio_from_b.m4a") }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "START" -> if (!Rec.running) start(i) else stopSelfIfIdle()
            "STOP" -> if (Rec.running) stopFlow() else stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopSelfIfIdle() { if (!Rec.running) stopSelf() }

    private fun start(i: Intent) {
        Rec.running = true
        val p = getSharedPreferences("d", MODE_PRIVATE)
        mac = p.getString("mac", "") ?: ""
        nudge = p.getLong("nudge", 0L)
        stopping.set(false)
        bStarted = false
        startForeground(1, note("Starting…", true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        try {
            @Suppress("DEPRECATION")
            val data: Intent = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)!!
            else i.getParcelableExtra("data")!!
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val pr = mpm.getMediaProjection(i.getIntExtra("code", 0), data)
            pr.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopFlow() }
            }, main)
            proj = pr
        } catch (e: Exception) {
            fail("Screen capture refused: ${e.message}")
            return
        }

        starter = thread {
            try {
                status("Connecting to the audio phone…")
                val l = Bt.connect(this, mac)
                val off: Long
                val tb: Long
                try {
                    off = l.clockOffset()          // audio phone clock minus this phone's clock
                    tb = l.start()                 // audio phone clock when its microphone started
                } finally { l.close() }
                if (tb < 0) throw IOException("The audio phone could not start its microphone")
                bStarted = true
                status("Starting screen recording…")
                startCapture()
                val ta = System.currentTimeMillis()                    // this phone's clock when the screen recording started
                offsetUs = (ta - (tb - off) - nudge) * 1000L           // how far into the audio the first video frame lies
                status("● Recording")
            } catch (e: Exception) {
                fail("Couldn't start: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun newRec(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun startCapture() {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val sc = minOf(1f, 1920f / maxOf(b.width(), b.height()))
        val w = (b.width() * sc).toInt() and 1.inv()
        val h = (b.height() * sc).toInt() and 1.inv()
        video.delete()
        val r = newRec()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(8_000_000)
        r.setOutputFile(video.path)
        r.prepare()
        vd = proj!!.createVirtualDisplay(
            "dualrec", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.start()
        mr = r
    }

    private fun stopFlow() {
        if (!stopping.compareAndSet(false, true)) return
        thread {
            try {
                starter?.join(30000)
                if (mr == null) { done("Nothing was recorded"); return@thread }
                status("Stopping…", false)
                try { mr?.stop() } catch (_: Exception) {}
                release()

                var haveAudio = false
                for (attempt in 1..4) {
                    try {
                        status("Getting audio over Bluetooth…", false)
                        audio.delete()
                        val l = Bt.connect(this, mac)
                        try { haveAudio = l.stop(audio) { pc -> status("Getting audio over Bluetooth… $pc%", false) } } finally { l.close() }
                        if (haveAudio && audio.length() > 1000) break
                    } catch (_: Exception) { Thread.sleep(1500) }
                }
                if (audio.length() < 1000) haveAudio = false

                status("Saving video…", false)
                val out = File(cacheDir, "final.mp4")
                Merger.merge(video, if (haveAudio) audio else null, offsetUs, out)
                val name = "Call_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                Saver.toGallery(this, out, name)
                done(if (haveAudio) "Saved ✔  Gallery → Movies/DualRec" else "Saved VIDEO ONLY (couldn't get audio from the audio phone)")
            } catch (e: Exception) {
                done("Failed: ${e.message}")
            }
        }
    }

    private fun fail(msg: String) {
        stopping.set(true)
        if (bStarted) {
            try { Bt.connect(this, mac).use { it.stop(audio) { } } } catch (_: Exception) {}
        }
        release()
        done(msg)
    }

    private fun release() {
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        try { mr?.release() } catch (_: Exception) {}
        mr = null
        try { proj?.stop() } catch (_: Exception) {}
        proj = null
    }

    private fun done(msg: String) {
        Rec.status = msg
        Rec.running = false
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(2, note(msg, false))
        starter = null
        stopSelf()
    }

    private fun status(msg: String, withStop: Boolean = true) {
        Rec.status = msg
        getSystemService(NotificationManager::class.java).notify(1, note(msg, withStop))
    }

    private fun note(text: String, withStop: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW))
        val b = Notification.Builder(this, "rec")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DualRec")
            .setContentText(text)
        if (withStop) {
            val pi = PendingIntent.getService(
                this, 0, Intent(this, RecorderService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop & save", pi).build())
        }
        return b.build()
    }
}
