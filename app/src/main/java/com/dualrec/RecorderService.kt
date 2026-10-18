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
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val MB = 1024L * 1024L

/** VIDEO phone: records the screen (no audio), then pulls the audio phone's recording over Bluetooth and merges. */
class RecorderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var proj: MediaProjection? = null
    private var mr: MediaRecorder? = null
    private var vd: VirtualDisplay? = null
    private var starter: Thread? = null
    private var wl: PowerManager.WakeLock? = null
    private val stopping = AtomicBoolean(false)
    @Volatile private var bStarted = false
    @Volatile private var watching = false
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

    override fun onDestroy() {
        watching = false
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun stopSelfIfIdle() { if (!Rec.running) stopSelf() }

    private fun start(i: Intent) {
        Rec.running = true
        Rec.saving = false
        Rec.startedAt = 0L
        Rec.status = "Starting…"
        val p = getSharedPreferences("d", MODE_PRIVATE)
        mac = p.getString("mac", "") ?: ""
        nudge = p.getLong("nudge", 0L)
        stopping.set(false)
        bStarted = false
        startForeground(1, note("Starting…", true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        Rec.changed(this)

        // keep the CPU awake for the whole (1-2 hour) recording, even if the screen dims
        try {
            wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dualrec:rec").also { it.acquire(6 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}

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

        if (cacheDir.usableSpace < 4096 * MB) {
            fail("Not enough free storage (keep at least 4 GB free; a 1 hour call needs ~8 GB)")
            return
        }

        starter = thread {
            try {
                status("Connecting to the audio phone…")
                val l = Bt.connect(this, mac)        // switches Bluetooth on first if needed
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
                Rec.startedAt = SystemClock.elapsedRealtime()
                status("● Recording")
                Rec.changed(this)
                watchStorage()
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
        // if the encoder dies (disk full, etc.) save what we have instead of losing everything
        r.setOnErrorListener { _, _, _ -> stopFlow() }
        r.prepare()
        vd = proj!!.createVirtualDisplay(
            "dualrec", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.start()
        mr = r
    }

    /** Every 15 s: if free space is nearly gone, stop & save now (merging needs about as much space as the video itself). */
    private fun watchStorage() {
        watching = true
        thread(isDaemon = true) {
            while (watching && !stopping.get()) {
                try { Thread.sleep(15000) } catch (_: InterruptedException) { break }
                if (!watching || stopping.get()) break
                if (cacheDir.usableSpace < video.length() + 700 * MB) {
                    Rec.status = "Storage almost full - saving now"
                    stopFlow()
                    break
                }
            }
        }
    }

    private fun stopFlow() {
        if (!stopping.compareAndSet(false, true)) return
        watching = false
        Rec.saving = true
        Rec.changed(this)
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
                val name = "Call_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                // merge straight into the gallery file: no extra multi-GB temp copy
                val uri = Saver.create(this, name)
                try {
                    val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Can't open gallery file")
                    pfd.use { Merger.merge(video, if (haveAudio) audio else null, offsetUs, it.fileDescriptor) }
                    Saver.publish(this, uri)
                } catch (e: Exception) {
                    Saver.discard(this, uri)
                    throw e
                }
                video.delete()
                audio.delete()
                done(if (haveAudio) "Saved ✔  Gallery → Movies/DualRec" else "Saved VIDEO ONLY (couldn't get audio from the audio phone)")
            } catch (e: Exception) {
                // last resort: never lose the screen video
                val rescued = try {
                    if (video.exists() && video.length() > 1000) {
                        Saver.copy(this, video, "Call_RAW_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
                        true
                    } else false
                } catch (_: Exception) { false }
                done(if (rescued) "Merge failed (${e.message}) - saved the raw screen video to Movies/DualRec" else "Failed: ${e.message}")
            }
        }
    }

    private fun fail(msg: String) {
        stopping.set(true)
        watching = false
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
        watching = false
        Rec.status = msg
        Rec.running = false
        Rec.saving = false
        Rec.startedAt = 0L
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
        wl = null
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(2, doneNote(msg))
        Rec.changed(this)
        starter = null
        stopSelf()
    }

    private fun status(msg: String, withStop: Boolean = true) {
        Rec.status = msg
        getSystemService(NotificationManager::class.java).notify(1, note(msg, withStop))
    }

    /**
     * Ongoing notification on a MIN-importance channel: no status-bar icon, no pop-up, nothing on the lock screen,
     * so it doesn't end up in the recorded video. (New channel id: Android keeps the importance of an old one.)
     */
    private fun note(text: String, withStop: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("rec_silent", "Recording (silent)", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
        val b = Notification.Builder(this, "rec_silent")
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("DualRec")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
        if (withStop && Rec.startedAt > 0) {
            b.setUsesChronometer(true).setShowWhen(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Rec.startedAt))
        }
        if (withStop) {
            val pi = PendingIntent.getService(
                this, 0, Intent(this, RecorderService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_rec), "Stop & save", pi).build())
        }
        return b.build()
    }

    private fun doneNote(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec_done", "Recording result", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "rec_done")
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("DualRec")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}
