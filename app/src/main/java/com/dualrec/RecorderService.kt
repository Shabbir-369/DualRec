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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val MB = 1024L * 1024L

/** The screen recording is cut into finished files of about this size (far below the 4 GB MP4 limit; a crash only risks the newest part). */
private const val PART_BYTES = 1800L * MB

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
    private var stamp = ""
    private var session: File? = null
    private val parts = CopyOnWriteArrayList<File>()

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
        RecLog.clear()
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
                override fun onStop() { RecLog.add("MediaProjection stopped by Android"); stopFlow() }
            }, main)
            proj = pr
        } catch (e: Exception) {
            fail("Screen capture refused: ${e.message}")
            return
        }

        val free = cacheDir.usableSpace
        RecLog.add("start: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}, free ${free / MB} MB")
        if (free < 4096 * MB) {
            val kept = Sessions.list(this).sumOf { it.bytes }
            fail("Not enough free storage (keep at least 4 GB free; a 1 hour call needs ~8 GB)" +
                if (kept > 0) ". An unsaved recording is using ${kept / MB} MB: open DualRec and tap Recover." else "")
            return
        }

        stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        session = Sessions.create(this, stamp)
        Sessions.active = session
        parts.clear()

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
                Sessions.writeMeta(session!!, offsetUs)
                Rec.startedAt = SystemClock.elapsedRealtime()
                status("● Recording")
                Rec.changed(this)
                watchStorage()
            } catch (e: Exception) {
                RecLog.add("start failed: $e")
                fail("Couldn't start: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun newRec(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun partFile(n: Int) = File(session!!, "screen_$n.mp4")

    private fun startCapture() {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val sc = minOf(1f, 1920f / maxOf(b.width(), b.height()))
        val w = (b.width() * sc).toInt() and 1.inv()
        val h = (b.height() * sc).toInt() and 1.inv()
        val first = partFile(1)
        parts.add(first)
        val r = newRec()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(8_000_000)
        r.setMaxFileSize(PART_BYTES)
        r.setOutputFile(first.path)
        r.setOnInfoListener { _, what, extra -> onRecorderInfo(what, extra) }
        // if the encoder dies (disk full, etc.) save what we have instead of losing everything
        r.setOnErrorListener { _, what, extra -> RecLog.add("MediaRecorder error $what/$extra"); stopFlow() }
        r.prepare()
        vd = proj!!.createVirtualDisplay(
            "dualrec", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.start()
        mr = r
    }

    /** Close to the part limit: tell the recorder which file to continue in (seamless, the finished part is closed properly). */
    private fun onRecorderInfo(what: Int, extra: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                try {
                    val next = partFile(parts.size + 1)
                    mr?.setNextOutputFile(next)
                    parts.add(next)
                    RecLog.add("next screen part armed: ${next.name}")
                } catch (e: Exception) {
                    RecLog.add("could not arm next part: $e")
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> RecLog.add("switched to next screen part")
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                RecLog.add("recorder limit reached ($what) - saving what we have")
                stopFlow()
            }
        }
    }

    /** Every 15 s: if free space is nearly gone, stop & save now (merging needs about as much space as the video itself). */
    private fun watchStorage() {
        watching = true
        thread(isDaemon = true) {
            while (watching && !stopping.get()) {
                try { Thread.sleep(15000) } catch (_: InterruptedException) { break }
                if (!watching || stopping.get()) break
                if (cacheDir.usableSpace < parts.sumOf { it.length() } + 700 * MB) {
                    RecLog.add("storage almost full - stopping")
                    Rec.status = "Storage almost full - saving now"
                    stopFlow()
                    break
                }
            }
        }
    }

    private fun buildSession(audio: File?): Session =
        Session(session!!, stamp, parts.toList(), audio?.takeIf { it.exists() && it.length() > 0 }, offsetUs, false)

    private fun stopFlow() {
        if (!stopping.compareAndSet(false, true)) return
        watching = false
        Rec.saving = true
        Rec.changed(this)
        thread {
            val audio = session?.let { File(it, "audio_from_b.m4a") }
            try {
                starter?.join(30000)
                if (mr == null) {
                    session?.deleteRecursively()
                    done("Nothing was recorded")
                    return@thread
                }
                status("Stopping…", false)
                if (Rec.startedAt > 0) {
                    val el = SystemClock.elapsedRealtime() - Rec.startedAt
                    if (el < 3000) Thread.sleep(3000 - el)      // a clip stopped too early can end up unreadable
                }
                try { mr?.stop() } catch (e: Exception) { RecLog.add("MediaRecorder.stop() threw: $e") }
                release()

                var haveAudio = false
                for (attempt in 1..4) {
                    try {
                        status("Getting audio over Bluetooth…", false)
                        audio!!.delete()
                        val l = Bt.connect(this, mac)
                        try { haveAudio = l.stop(audio) { pc -> status("Getting audio over Bluetooth… $pc%", false) } } finally { l.close() }
                        if (haveAudio && Merger.readable(audio, "audio/")) break
                        RecLog.add("audio attempt $attempt: transferred=$haveAudio, check=${Merger.probe(audio, "audio/")}")
                    } catch (e: Exception) {
                        RecLog.add("audio attempt $attempt failed: $e")
                        Thread.sleep(1500)
                    }
                }

                val msg = Recovery.finish(this, buildSession(audio)) { status(it, false) }
                done(msg)
            } catch (e: Throwable) {
                // last resort: everything is still in the session folder -> copy it out as it is
                RecLog.add("stop pipeline crashed: $e")
                val msg = try {
                    release()
                    Recovery.saveRaw(this, buildSession(audio)) { status(it, false) }
                } catch (e2: Throwable) {
                    "Failed (${e.message}). Your recording is kept safely inside DualRec - open the app and tap Recover."
                }
                Saver.saveLog(this, "Call_${stamp}_log.txt", RecLog.text())
                done(msg)
            }
        }
    }

    private fun fail(msg: String) {
        stopping.set(true)
        watching = false
        if (bStarted) {
            val tmp = File(cacheDir, "discard.m4a")
            try { Bt.connect(this, mac).use { it.stop(tmp) { } } } catch (_: Exception) {}
            tmp.delete()
        }
        release()
        session?.deleteRecursively()
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
        Sessions.active = null
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
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}