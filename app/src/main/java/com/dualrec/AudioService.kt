package com.dualrec

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.concurrent.thread

/** AUDIO phone: Bluetooth server. 'T' = clock, 'S' = start microphone, 'E' = stop microphone and send the file. */
@SuppressLint("MissingPermission")
class AudioService : Service() {
    companion object {
        @Volatile var up = false
        @Volatile var status = "Waiting for the video phone…"
    }

    private var server: BluetoothServerSocket? = null
    private var rec: MediaRecorder? = null
    private var wl: PowerManager.WakeLock? = null
    private val file by lazy { File(cacheDir, "audio_b.m4a") }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "QUIT") { stopSelf(); return START_NOT_STICKY }
        startForeground(5, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!up) {
            up = true
            status = "Waiting for the video phone…"
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dualrec:audio")
            wl?.acquire(8 * 60 * 60 * 1000L)
            thread { serve() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        up = false
        stopRec()
        try { server?.close() } catch (_: Exception) {}
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
    }

    private fun serve() {
        while (up) {
            try {
                val a = Bt.adapter(this) ?: throw IllegalStateException("No Bluetooth")
                val ss = a.listenUsingRfcommWithServiceRecord("DualRec", APP_UUID)
                server = ss
                while (up) {
                    val c = ss.accept()
                    thread {
                        try { handle(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} }
                    }
                }
            } catch (e: Exception) {
                if (!up) break
                status = "Bluetooth problem (is it on?) — retrying…"
                try { Thread.sleep(2000) } catch (_: Exception) {}
            }
        }
    }

    private fun handle(c: BluetoothSocket) {
        val i = DataInputStream(c.inputStream)
        val o = DataOutputStream(c.outputStream)
        while (true) {
            val cmd = i.read()
            if (cmd < 0) break
            when (cmd.toChar()) {
                'T' -> { o.writeLong(System.currentTimeMillis()); o.flush() }
                'S' -> {
                    val t = try { startRec() } catch (e: Exception) { status = "Microphone error: ${e.message}"; -1L }
                    if (t > 0) status = "● Recording the speaker…"
                    o.writeLong(t); o.flush()
                }
                'E' -> {
                    stopRec()
                    if (file.exists() && file.length() > 0) {
                        status = "Sending audio…"
                        o.writeLong(file.length())
                        file.inputStream().use { it.copyTo(o, 65536) }
                        o.flush()
                        status = "Audio sent ✔ — waiting for the next recording"
                    } else { o.writeLong(-1); o.flush() }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun newRec(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    @Synchronized
    private fun startRec(): Long {
        stopRec()
        file.delete()
        val r = newRec()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(64000)
        r.setOutputFile(file.path)
        r.prepare()
        r.start()
        rec = r
        return System.currentTimeMillis()
    }

    @Synchronized
    private fun stopRec() {
        rec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        rec = null
    }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("aud", "Audio phone", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getService(
            this, 1, Intent(this, AudioService::class.java).setAction("QUIT"), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "aud")
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("DualRec audio phone")
            .setContentText("Listening for the video phone")
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_rec), "Quit", pi).build())
            .build()
    }
}
