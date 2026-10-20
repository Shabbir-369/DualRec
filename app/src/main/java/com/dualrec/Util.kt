package com.dualrec

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.service.quicksettings.TileService
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared state of the VIDEO phone's recording (same process as the service, tile, widget and activity). */
object Rec {
    @Volatile var running = false          // true from the moment recording is requested until everything is saved
    @Volatile var saving = false           // true while stopping / fetching audio / merging
    @Volatile var recovering = false       // true while an old unsaved recording is being recovered
    @Volatile var startedAt = 0L           // SystemClock.elapsedRealtime() when the screen capture began (0 = not yet)
    @Volatile var status = "Ready"

    /** Tell the widget and the Quick Settings tile to redraw. */
    fun changed(ctx: Context) {
        try { RecWidget.refresh(ctx) } catch (_: Exception) {}
        try { TileService.requestListeningState(ctx, ComponentName(ctx, RecTile::class.java)) } catch (_: Exception) {}
    }
}

/** In-memory diary of what happened; saved as a .txt next to the recording whenever something went wrong. */
object RecLog {
    private val sb = StringBuilder()
    @Synchronized fun clear() { sb.setLength(0) }
    @Synchronized fun add(m: String) {
        sb.append(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())).append("  ").append(m).append('\n')
    }
    @Synchronized fun text(): String = sb.toString()
}

/** Muxes the silent screen video(s) with the audio phone's recording, with no re-encoding. */
object Merger {
    private fun pick(x: MediaExtractor, prefix: String): Int =
        (0 until x.trackCount).first { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }

    /** null = file is fine; otherwise the reason Android can't use it. */
    fun probe(f: File, prefix: String): String? {
        if (!f.exists()) return "missing"
        if (f.length() < 1000) return "empty (${f.length()} bytes)"
        val x = MediaExtractor()
        return try {
            x.setDataSource(f.path)
            val ok = (0 until x.trackCount).any { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
            if (ok) null else "no ${prefix.trimEnd('/')} track inside"
        } catch (e: Exception) {
            "cannot be opened (${e.message})"
        } finally {
            try { x.release() } catch (_: Exception) {}
        }
    }

    fun readable(f: File, prefix: String): Boolean = probe(f, prefix) == null

    /** Re-opens the finished gallery file and checks it really contains video (and audio if expected). */
    fun verifyOutput(ctx: Context, uri: Uri, expectAudio: Boolean): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val x = MediaExtractor()
            try {
                x.setDataSource(pfd.fileDescriptor)
                var v = false
                var a = false
                for (i in 0 until x.trackCount) {
                    val m = x.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                    if (m.startsWith("video/")) v = true
                    if (m.startsWith("audio/")) a = true
                }
                v && (!expectAudio || a)
            } finally {
                try { x.release() } catch (_: Exception) {}
            }
        } ?: false
    } catch (e: Exception) {
        RecLog.add("verify failed: $e")
        false
    }

    /**
     * videos = screen parts in order (played back to back). audioOffsetUs = how far into the audio recording the
     * first video frame happened. Writes straight into [out].
     */
    fun merge(videos: List<File>, audio: File?, audioOffsetUs: Long, out: FileDescriptor) {
        require(videos.isNotEmpty())
        val vx = MediaExtractor()
        var ax: MediaExtractor? = null
        var mux: MediaMuxer? = null
        try {
            vx.setDataSource(videos[0].path)
            val vf = vx.getTrackFormat(pick(vx, "video/"))
            var af: MediaFormat? = null
            if (audio != null) {
                val x = MediaExtractor()
                ax = x
                x.setDataSource(audio.path)
                val ai = pick(x, "audio/")
                x.selectTrack(ai)
                af = x.getTrackFormat(ai)
            }
            mux = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = mux.addTrack(vf)
            val at = if (af != null) mux.addTrack(af) else -1
            mux.start()

            var base = 0L                                   // where the next screen part starts on the timeline
            for ((n, f) in videos.withIndex()) {
                val x = if (n == 0) vx else MediaExtractor().also { it.setDataSource(f.path) }
                try {
                    val i = pick(x, "video/")
                    x.selectTrack(i)
                    val fmt = x.getTrackFormat(i)
                    val last = copy(x, mux, vt, 0L, base, Long.MAX_VALUE)
                    val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                    base = maxOf(base + dur, last + 33_000L)
                } finally {
                    if (n != 0) try { x.release() } catch (_: Exception) {}
                }
            }
            if (ax != null) {
                if (audioOffsetUs > 0) ax.seekTo(audioOffsetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                copy(ax, mux, at, audioOffsetUs, 0L, base)
            }
            mux.stop()
        } finally {
            try { mux?.release() } catch (_: Exception) {}
            try { vx.release() } catch (_: Exception) {}
            try { ax?.release() } catch (_: Exception) {}
        }
    }

    /** Copies samples; time written = sampleTime - shiftUs + addUs. Returns the last time written. */
    private fun copy(x: MediaExtractor, m: MediaMuxer, track: Int, shiftUs: Long, addUs: Long, maxUs: Long): Long {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var last = addUs
        while (true) {
            val n = x.readSampleData(buf, 0)
            if (n < 0) break
            val rel = x.sampleTime - shiftUs
            if (rel > maxUs) break
            if (rel >= 0) {
                val flags = if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, rel + addUs, flags)
                m.writeSampleData(track, buf, info)
                last = rel + addUs
            }
            x.advance()
        }
        return last
    }
}

object Saver {
    private fun createVideo(ctx: Context, name: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Can't create gallery entry")
    }

    /** Creates a pending gallery entry (Movies/DualRec). Write to it, then call [publish]. */
    fun create(ctx: Context, name: String): Uri = createVideo(ctx, name)

    fun publish(ctx: Context, uri: Uri) {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, cv, null, null)
    }

    fun discard(ctx: Context, uri: Uri) {
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    private fun verifySize(ctx: Context, uri: Uri, expected: Long) {
        val len = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (len >= 0 && len != expected) throw IOException("copy incomplete ($len of $expected bytes)")
    }

    /** Byte-for-byte copy of a screen file into Movies/DualRec, size-checked. Throws on any problem. */
    fun copyVideo(ctx: Context, f: File, name: String) {
        val u = createVideo(ctx, name)
        try {
            ctx.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 20) } }
            verifySize(ctx, u, f.length())
            publish(ctx, u)
        } catch (e: Exception) { discard(ctx, u); throw e }
    }

    /** Byte-for-byte copy of an audio file into Music/DualRec, size-checked. Throws on any problem. */
    fun copyAudio(ctx: Context, f: File, name: String) {
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DualRec")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val u = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Can't create audio entry")
        try {
            ctx.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 20) } }
            verifySize(ctx, u, f.length())
            publish(ctx, u)
        } catch (e: Exception) { discard(ctx, u); throw e }
    }

    /** Writes the diary to Download/DualRec so a failed recording can be diagnosed. Best effort. */
    fun saveLog(ctx: Context, name: String, text: String) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DualRec")
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            ctx.contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) {}
    }
}