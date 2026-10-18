package com.dualrec

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

val APP_UUID: UUID = UUID.fromString("7c1d6c3a-5e0f-4e0e-9b53-3d6e0b2a8f11")

@SuppressLint("MissingPermission")
object Bt {
    fun adapter(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Tries to switch Bluetooth on silently. Returns false if Android refuses (then a system prompt is needed). */
    @Suppress("DEPRECATION")
    fun tryEnable(ctx: Context): Boolean = try {
        val a = adapter(ctx)
        a != null && (a.isEnabled || a.enable())
    } catch (_: Exception) { false }

    /** Blocks (call from a worker thread) until Bluetooth is fully on. */
    fun waitOn(ctx: Context, timeoutMs: Long): Boolean {
        val a = adapter(ctx) ?: return false
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (a.state == BluetoothAdapter.STATE_ON) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
        }
        return a.state == BluetoothAdapter.STATE_ON
    }

    /**
     * Opens a Bluetooth link from the VIDEO phone to the AUDIO phone (already paired in Android settings).
     * Turns Bluetooth on first if it is off, and retries longer right after switching it on.
     */
    fun connect(ctx: Context, mac: String): Link {
        val a = adapter(ctx) ?: throw IOException("This phone has no Bluetooth")
        var fresh = false
        if (a.state != BluetoothAdapter.STATE_ON) {
            if (a.state == BluetoothAdapter.STATE_OFF && !tryEnable(ctx))
                throw IOException("Bluetooth is off - switch it on and try again")
            if (!waitOn(ctx, 15000)) throw IOException("Bluetooth did not turn on")
            fresh = true
            Thread.sleep(1500)          // let the stack settle before the first connect
        }
        val dev = a.getRemoteDevice(mac)
        var last = ""
        repeat(if (fresh) 8 else 5) {
            var s: BluetoothSocket? = null
            try {
                s = dev.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                return Link(s)
            } catch (e: Exception) {
                last = e.message ?: ""
                try { s?.close() } catch (_: Exception) {}
                Thread.sleep(if (fresh) 1200 else 800)
            }
        }
        throw IOException("Can't reach the audio phone. Is DualRec open there, and Bluetooth on? ($last)")
    }
}

/** Tiny request/response protocol: 'T' = time, 'S' = start mic, 'E' = stop mic and send the file. */
class Link(private val s: BluetoothSocket) : Closeable {
    private val i = DataInputStream(s.inputStream)
    private val o = DataOutputStream(s.outputStream)
    @Volatile private var lastActive = System.currentTimeMillis()
    @Volatile private var closed = false

    init {
        // watchdog: if the other phone goes silent for 20 s, cut the link instead of hanging forever
        thread(isDaemon = true) {
            while (!closed) {
                Thread.sleep(1000)
                if (System.currentTimeMillis() - lastActive > 20000) { close(); break }
            }
        }
    }

    private fun cmd(c: Char) { lastActive = System.currentTimeMillis(); o.writeByte(c.code); o.flush() }

    fun time(): Long { cmd('T'); val v = i.readLong(); lastActive = System.currentTimeMillis(); return v }

    /** Returns the audio phone's clock (ms) at the moment its microphone started, or -1 on failure. */
    fun start(): Long { cmd('S'); val v = i.readLong(); lastActive = System.currentTimeMillis(); return v }

    /** Stops the audio phone's recording and downloads it. */
    fun stop(dest: File, progress: (Int) -> Unit): Boolean {
        cmd('E')
        val n = i.readLong()
        if (n <= 0) return false
        dest.outputStream().use { out ->
            val buf = ByteArray(65536)
            var got = 0L
            while (got < n) {
                val r = i.read(buf, 0, minOf(buf.size.toLong(), n - got).toInt())
                if (r < 0) throw IOException("Bluetooth link dropped")
                out.write(buf, 0, r)
                got += r
                lastActive = System.currentTimeMillis()
                progress((got * 100 / n).toInt())
            }
        }
        return true
    }

    /** (audio phone clock) minus (this phone's clock), in ms. Uses the 5 fastest of 20 pings and takes the median. */
    fun clockOffset(): Long {
        val samples = ArrayList<Pair<Long, Long>>()
        repeat(20) {
            val t0 = System.currentTimeMillis()
            val tb = time()
            val t1 = System.currentTimeMillis()
            samples.add(Pair(t1 - t0, tb - (t0 + t1) / 2))
        }
        val best = samples.sortedBy { it.first }.take(5).map { it.second }.sorted()
        return best[best.size / 2]
    }

    override fun close() { closed = true; try { s.close() } catch (_: Exception) {} }
}
