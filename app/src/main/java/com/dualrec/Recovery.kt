package com.dualrec

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One recording kept inside the app until it is safely stored: screen part(s) + audio. */
class Session(
    val dir: File,
    val stamp: String,
    val videos: List<File>,
    val audio: File?,
    val offsetUs: Long,
    val legacy: Boolean          // files from older app versions that lived directly in the cache folder
) {
    val bytes: Long get() = videos.sumOf { it.length() } + (audio?.length() ?: 0L)
}

object Sessions {
    @Volatile var active: File? = null       // the recording in progress is never offered for recovery
    private fun root(ctx: Context) = File(ctx.cacheDir, "sessions")

    fun create(ctx: Context, stamp: String): File = File(root(ctx), stamp).also { it.mkdirs() }

    fun writeMeta(dir: File, offsetUs: Long) {
        try { File(dir, "meta.txt").writeText("offsetUs=$offsetUs\n") } catch (_: Exception) {}
    }

    private fun readOffset(dir: File): Long = try {
        File(dir, "meta.txt").readLines().firstOrNull { it.startsWith("offsetUs=") }?.substringAfter("=")?.trim()?.toLong() ?: 0L
    } catch (_: Exception) { 0L }

    fun segIndex(f: File): Int = f.name.removePrefix("screen_").removeSuffix(".mp4").toIntOrNull() ?: 1

    fun list(ctx: Context): List<Session> {
        val out = ArrayList<Session>()
        root(ctx).listFiles()?.filter { it.isDirectory && it != active }?.sortedByDescending { it.name }?.forEach { d ->
            val vids = d.listFiles { f -> f.name.startsWith("screen_") && f.name.endsWith(".mp4") && f.length() > 0 }
                ?.sortedBy { segIndex(it) } ?: emptyList()
            val a = File(d, "audio_from_b.m4a").takeIf { it.exists() && it.length() > 0 }
            if (vids.isEmpty() && a == null) d.deleteRecursively()
            else out.add(Session(d, d.name, vids, a, readOffset(d), false))
        }
        // files left by older versions (before sessions existed)
        val lv = File(ctx.cacheDir, "screen.mp4")
        val la = File(ctx.cacheDir, "audio_from_b.m4a")
        val hasV = lv.exists() && lv.length() > 1000
        val hasA = la.exists() && la.length() > 1000
        if (hasV || hasA) {
            val t = maxOf(if (hasV) lv.lastModified() else 0L, if (hasA) la.lastModified() else 0L)
            out.add(Session(
                ctx.cacheDir, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(t)),
                if (hasV) listOf(lv) else emptyList(), if (hasA) la else null,
                1_000_000L,           // unknown for old recordings: assume the screen started ~1 s after the microphone
                true
            ))
        }
        return out
    }

    fun delete(s: Session) {
        try {
            if (s.legacy) { s.videos.forEach { it.delete() }; s.audio?.delete() } else s.dir.deleteRecursively()
        } catch (_: Exception) {}
    }
}

/**
 * The "never lose data" part. Whatever happens, the recording stays inside the app until it has been
 * (a) merged AND re-opened successfully, or (b) copied byte-for-byte to the phone's public storage.
 */
object Recovery {
    private fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1e9)

    private fun rawVideoName(s: Session, f: File, total: Int) =
        if (total == 1) "Call_${s.stamp}_screen.mp4" else "Call_${s.stamp}_screen_part${Sessions.segIndex(f)}.mp4"

    /** Copies the given files to public storage as they are. Returns how many were saved and whether ALL succeeded. */
    private fun exportRaw(ctx: Context, s: Session, vids: List<File>, audio: File?): Pair<Int, Boolean> {
        var saved = 0
        var all = true
        for (f in vids) {
            try { Saver.copyVideo(ctx, f, rawVideoName(s, f, vids.size)); saved++ }
            catch (e: Exception) { all = false; RecLog.add("saving ${f.name} separately failed: $e") }
        }
        if (audio != null) {
            try { Saver.copyAudio(ctx, audio, "Call_${s.stamp}_audio.m4a"); saved++ }
            catch (e: Exception) { all = false; RecLog.add("saving audio separately failed: $e") }
        }
        return saved to all
    }

    private fun tryMerge(ctx: Context, s: Session, vids: List<File>, aud: File?, status: (String) -> Unit): Boolean {
        status(if (aud != null) "Merging video + audio…" else "Saving video…")
        val uri = try { Saver.create(ctx, "Call_${s.stamp}.mp4") } catch (e: Exception) { RecLog.add("create failed: $e"); return false }
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw java.io.IOException("cannot open output file")
            pfd.use { Merger.merge(vids, aud, s.offsetUs, it.fileDescriptor) }
            if (!Merger.verifyOutput(ctx, uri, aud != null)) throw java.io.IOException("merged file failed the re-open check")
            Saver.publish(ctx, uri)
            RecLog.add("merge ok (audio=${aud != null})")
            true
        } catch (e: Throwable) {
            RecLog.add("merge failed (audio=${aud != null}): $e")
            Saver.discard(ctx, uri)
            false
        }
    }

    /** Merge if possible; save whatever couldn't be merged separately; keep everything inside the app if even that fails. */
    fun finish(ctx: Context, s: Session, status: (String) -> Unit): String {
        RecLog.add("finish ${s.stamp}: screen=${s.videos.map { "${it.name}:${it.length()}" }} audio=${s.audio?.length()} offsetUs=${s.offsetUs}")
        val usable = s.videos.filter { it.length() > 1000 }
        if (usable.isEmpty() && (s.audio?.length() ?: 0L) <= 1000L) { Sessions.delete(s); return "Nothing was recorded" }

        val vids = s.videos.filter { f -> Merger.probe(f, "video/").also { if (it != null) RecLog.add("screen ${f.name}: $it") } == null }
        val aud = s.audio?.takeIf { f -> Merger.probe(f, "audio/").also { if (it != null) RecLog.add("audio: $it") } == null }

        var merged = false
        var withAudio = false
        if (vids.isNotEmpty()) {
            if (aud != null && tryMerge(ctx, s, vids, aud, status)) { merged = true; withAudio = true }
            else if (tryMerge(ctx, s, vids, null, status)) merged = true
        }

        // anything that is not inside the merged file gets saved separately
        val mergedVideos = if (merged) vids else emptyList()
        val rawVideos = s.videos.filter { it !in mergedVideos && it.length() > 1000 }
        val rawAudio = if (withAudio) null else s.audio?.takeIf { it.length() > 1000 }
        status("Saving the remaining files separately…")
        val (savedRaw, allSafe) = if (rawVideos.isNotEmpty() || rawAudio != null) exportRaw(ctx, s, rawVideos, rawAudio) else 0 to true

        val clean = merged && withAudio && rawVideos.isEmpty() && rawAudio == null
        val videoOnlyNoAudio = merged && !withAudio && s.audio == null && rawVideos.isEmpty()
        if (allSafe) Sessions.delete(s)
        if (!clean) Saver.saveLog(ctx, "Call_${s.stamp}_log.txt", RecLog.text())

        return when {
            clean -> "Saved ✔  Gallery → Movies/DualRec"
            videoOnlyNoAudio && allSafe ->
                "Saved VIDEO ONLY - no audio arrived from the audio phone. Its copy is still there: open DualRec on that phone → \"Save last audio to phone\" (before recording again)."
            !allSafe ->
                "⚠ Some files could NOT be saved (not enough space?). They are kept safely inside DualRec - free up space, then open the app and tap Recover."
            else -> buildString {
                append(if (merged) "Merged video saved to Movies/DualRec. " else "Couldn't merge. ")
                if (rawVideos.isNotEmpty()) append("${rawVideos.size} screen file(s) saved separately in Movies/DualRec. ")
                if (rawAudio != null) append("Audio saved separately in Music/DualRec. ")
                append("Details: Download/DualRec.")
            }
        }
    }

    /** Saves every file as it is, without merging. */
    fun saveRaw(ctx: Context, s: Session, status: (String) -> Unit): String {
        status("Saving files…")
        val vids = s.videos.filter { it.length() > 1000 }
        val aud = s.audio?.takeIf { it.length() > 1000 }
        if (vids.isEmpty() && aud == null) { Sessions.delete(s); return "Nothing to save" }
        val size = gb(s.bytes)
        val (n, ok) = exportRaw(ctx, s, vids, aud)
        if (ok) Sessions.delete(s)
        return if (ok) "Saved $n file(s): screen → Movies/DualRec, audio → Music/DualRec ($size)"
        else "⚠ Not everything could be saved (not enough space?). Files are still kept inside DualRec."
    }
}