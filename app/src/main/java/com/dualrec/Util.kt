package com.dualrec

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Shared state of the VIDEO phone's recording (same process as the service, tile and activity). */
object Rec {
    @Volatile var running = false          // true from the moment recording is requested until the final file is saved
    @Volatile var status = "Ready"
}

/** Muxes the silent screen video with the audio phone's recording, with no re-encoding. */
object Merger {
    private fun pick(x: MediaExtractor, prefix: String): Int =
        (0 until x.trackCount).first { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }

    /** audioOffsetUs = how far into the audio recording the video's first frame happened. */
    fun merge(video: File, audio: File?, audioOffsetUs: Long, out: File) {
        out.delete()
        val vx = MediaExtractor()
        vx.setDataSource(video.path)
        val vi = pick(vx, "video/")
        vx.selectTrack(vi)
        val vf = vx.getTrackFormat(vi)
        val vDur = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE

        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vt = mux.addTrack(vf)
        val ax = if (audio != null) MediaExtractor().also { it.setDataSource(audio.path) } else null
        var at = -1
        if (ax != null) {
            val ai = pick(ax, "audio/")
            ax.selectTrack(ai)
            at = mux.addTrack(ax.getTrackFormat(ai))
        }
        mux.start()
        copy(vx, mux, vt, 0L, Long.MAX_VALUE)
        if (ax != null) {
            if (audioOffsetUs > 0) ax.seekTo(audioOffsetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            copy(ax, mux, at, audioOffsetUs, vDur)
        }
        mux.stop()
        mux.release()
        vx.release()
        ax?.release()
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
    fun toGallery(ctx: Context, f: File, name: String) {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Can't create gallery entry")
        r.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        cv.clear()
        cv.put(MediaStore.Video.Media.IS_PENDING, 0)
        r.update(uri, cv, null, null)
    }
}
