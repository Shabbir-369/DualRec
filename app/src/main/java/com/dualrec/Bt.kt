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

    /** Opens a Bluetooth link from the VIDEO phone to the AUDIO phone (already paired in Android settings). */
    fun connect(ctx: Context, mac: String): Link {
        val a = adapter(ctx) ?: throw IOException("This phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Turn Bluetooth on")
        val dev = a.getRemoteDevice(mac)
        var last = ""
        repeat(4) {
            var s: BluetoothSocket? = null
            try {
                s = dev.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                return Link(s)
            } catch (e: Exception) {
                last = e.message ?: ""
                try { s?.close() } catch (_: Exception) {}
                Thread.sleep(800)
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
