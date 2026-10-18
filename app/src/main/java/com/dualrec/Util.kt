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

/** Shared state of the VIDEO phone's recording (same process as the service, tile, widget and activity). */
object Rec {
    @Volatile var running = false          // true from the moment recording is requested until the final file is saved
    @Volatile var saving = false           // true while stopping / fetching audio / merging
    @Volatile var startedAt = 0L           // SystemClock.elapsedRealtime() when the screen capture began (0 = not yet)
    @Volatile var status = "Ready"

    /** Tell the widget and the Quick Settings tile to redraw. */
    fun changed(ctx: Context) {
        try { RecWidget.refresh(ctx) } catch (_: Exception) {}
        try { TileService.requestListeningState(ctx, ComponentName(ctx, RecTile::class.java)) } catch (_: Exception) {}
    }
}

/** Muxes the silent screen video with the audio phone's recording, with no re-encoding. */
object Merger {
    private fun pick(x: MediaExtractor, prefix: String): Int =
        (0 until x.trackCount).first { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }

    /** audioOffsetUs = how far into the audio recording the video's first frame happened. Writes straight into [out]. */
    fun merge(video: File, audio: File?, audioOffsetUs: Long, out: FileDescriptor) {
        val vx = MediaExtractor()
        var ax: MediaExtractor? = null
        var mux: MediaMuxer? = null
        try {
            vx.setDataSource(video.path)
            val vi = pick(vx, "video/")
            vx.selectTrack(vi)
            val vf = vx.getTrackFormat(vi)
            val vDur = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE

            mux = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = mux.addTrack(vf)
            var at = -1
            if (audio != null) {
                val x = MediaExtractor()
                ax = x
                x.setDataSource(audio.path)
                val ai = pick(x, "audio/")
                x.selectTrack(ai)
                at = mux.addTrack(x.getTrackFormat(ai))
            }
            mux.start()
            copy(vx, mux, vt, 0L, Long.MAX_VALUE)
            if (ax != null) {
                if (audioOffsetUs > 0) ax.seekTo(audioOffsetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                copy(ax, mux, at, audioOffsetUs, vDur)
            }
            mux.stop()
        } finally {
            try { mux?.release() } catch (_: Exception) {}
            try { vx.release() } catch (_: Exception) {}
            try { ax?.release() } catch (_: Exception) {}
        }
    }

    private fun copy(x: MediaExtractor, m: MediaMuxer, track: Int, shiftUs: Long, maxUs: Long) {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val n = x.readSampleData(buf, 0)
            if (n < 0) break
            val pts = x.sampleTime - shiftUs
            if (pts > maxUs) break
            if (pts >= 0) {
                val flags = if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, pts, flags)
                m.writeSampleData(track, buf, info)
            }
            x.advance()
        }
    }
}

object Saver {
    /** Creates a pending gallery entry (Movies/DualRec). Write to it, then call [publish]. */
    fun create(ctx: Context, name: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Can't create gallery entry")
    }

    fun publish(ctx: Context, uri: Uri) {
        val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, cv, null, null)
    }

    fun discard(ctx: Context, uri: Uri) {
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    /** Plain file copy into the gallery (used to rescue the raw screen video if merging fails). */
    fun copy(ctx: Context, f: File, name: String) {
        val u = create(ctx, name)
        try {
            ctx.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 20) } }
            publish(ctx, u)
        } catch (e: Exception) { discard(ctx, u); throw e }
    }
}
