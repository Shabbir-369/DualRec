package com.dualrec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

private val BG = 0xFF0E1015.toInt()
private val CARD = 0xFF171A22.toInt()
private val TEXT = 0xFFF2F4F8.toInt()
private val SUB = 0xFF9AA3B2.toInt()
private val RED = 0xFFFF3B4E.toInt()
private val GREEN = 0xFF3DDC97.toInt()
private val AMBER = 0xFFFFB020.toInt()
private val ACCENT = 0xFF4C8DFF.toInt()

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private lateinit var p: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var statusTv: TextView? = null
    private var timerTv: TextView? = null
    private var btChip: TextView? = null
    private var recBtn: RecButton? = null
    private var mainBtn: Button? = null
    private var nudgeTv: TextView? = null

    private fun role() = p.getString("role", "") ?: ""
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private val ticker = object : Runnable {
        override fun run() { tick(); ui.postDelayed(this, 500) }
    }

    // ---------- tiny UI kit (no libraries needed) ----------
    private fun shape(color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun label(t: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun lp(w: Int = -1, h: Int = -2, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun button(t: String, fill: Int = CARD, color: Int = TEXT, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(color); textSize = 15f
        stateListAnimator = null
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF), shape(fill, 14, if (fill == CARD) 0x22FFFFFF else 0), null
        )
        minHeight = dp(52); minimumHeight = dp(52)
        layoutParams = lp(top = 10)
        setOnClickListener { f() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, 0x14FFFFFF)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(top = 12)
    }

    private fun spacer(weight: Float) = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, weight) }

    // ---------- lifecycle ----------
    @Suppress("DEPRECATION")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(BG))
        window.statusBarColor = BG
        window.navigationBarColor = BG
        p = getSharedPreferences("d", MODE_PRIVATE)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (role() == "B") startListeningIfReady()
        ui.post(ticker)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---------- permissions ----------
    private fun required(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (role() == "B") l.add(Manifest.permission.RECORD_AUDIO)
        return l.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun askPermissions(): Boolean {
        val l = ArrayList(required())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            l.add(Manifest.permission.POST_NOTIFICATIONS)
        if (l.isNotEmpty()) requestPermissions(l.toTypedArray(), 10)
        return required().isEmpty()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        render()
        if (role() == "B") startListeningIfReady()
    }

    // ---------- screens ----------
    private fun render() {
        // anything this app shows is blacked out in screen recordings (and screenshots) on the video phone
        if (role() == "A") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        statusTv = null; timerTv = null; btChip = null; recBtn = null; mainBtn = null; nudgeTv = null
        when (role()) {
            "A" -> videoScreen(root)
            "B" -> audioScreen(root)
            else -> chooseScreen(root)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            setBackgroundColor(BG)
            addView(root, ViewGroup.LayoutParams(-1, -1))
        })
        tick()
    }

    private fun header(root: LinearLayout, sub: String?, withChip: Boolean = false) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("DualRec", 26f, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (withChip) {
            btChip = label("", 12f, SUB, true).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
            row.addView(btChip)
        }
        root.addView(row)
        if (sub != null) root.addView(label(sub, 14f, SUB).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun chooseScreen(root: LinearLayout) {
        header(root, null)
        root.addView(label("Which phone is this?", 20f, TEXT, true).apply { setPadding(0, dp(24), 0, dp(4)) })
        root.addView(label("You only choose this once on each phone.", 14f, SUB))
        root.addView(button("📱  VIDEO phone  (the one on the call)", ACCENT, 0xFFFFFFFF.toInt()) {
            p.edit().putString("role", "A").apply(); askPermissions(); render()
        }.apply { layoutParams = lp(top = 24) })
        root.addView(button("🎙  AUDIO phone  (next to the speaker)") {
            p.edit().putString("role", "B").apply(); askPermissions(); render()
        })
    }

    private fun videoScreen(root: LinearLayout) {
        val mac = p.getString("mac", "") ?: ""
        if (mac.isEmpty()) { setupScreen(root); return }

        header(root, "Audio phone · ${p.getString("macname", mac)}", withChip = true)
        root.addView(spacer(1f))

        val dm = resources.displayMetrics
        val size = minOf((minOf(dm.widthPixels, dm.heightPixels) * 0.62f).toInt(), dp(280))
        recBtn = RecButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
            setOnClickListener { onRec() }
        }
        root.addView(recBtn)
        timerTv = label(" ", 34f, TEXT, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(timerTv, lp())
        statusTv = label("", 14f, SUB).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), 0) }
        root.addView(statusTv, lp())
        root.addView(spacer(1f))

        val leftovers = if (Rec.running) emptyList() else Sessions.list(this)
        if (leftovers.isNotEmpty()) {
            val gb = String.format(Locale.US, "%.1f", leftovers.sumOf { it.bytes } / 1e9)
            root.addView(card().apply {
                background = shape(CARD, 18, AMBER)
                addView(label("⚠ Unsaved recording found", 15f, AMBER, true))
                addView(label(
                    "${leftovers.size} recording(s), $gb GB, are kept safely inside DualRec (nothing is lost). " +
                    "Tap Merge & save. If merging isn't possible, the screen video and audio are saved separately.", 13f, SUB
                ).apply { setPadding(0, dp(4), 0, 0) })
                addView(button("Merge & save", ACCENT, 0xFFFFFFFF.toInt()) { recover(true) })
                addView(button("Save the raw files separately") { recover(false) })
            })
        }

        root.addView(card().apply {
            addView(label("One-tap start", 14f, TEXT, true))
            addView(label(
                "Add the Dual Rec widget to your home screen, or the tile to Quick Settings. " +
                "One tap turns Bluetooth on, connects to the audio phone and records. " +
                "Android always asks “Start now” for screen capture — that one can't be skipped.", 13f, SUB
            ).apply { setPadding(0, dp(4), 0, 0) })
        })

        // ----- settings (hidden by default so the screen stays a single big button) -----
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val toggle = label("Settings  ▾", 14f, ACCENT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8)) }
        toggle.setOnClickListener {
            val show = box.visibility != View.VISIBLE
            box.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "Settings  ▴" else "Settings  ▾"
        }
        root.addView(toggle, lp())
        root.addView(box, lp())

        box.addView(button("Add widget to home screen") { pinWidget() })
        box.addView(button("Test Bluetooth connection") { testConnection() })

        box.addView(card().apply {
            addView(label("Fine-tune sync", 14f, TEXT, true))
            addView(label("Only if voices sound late/early in a test video. + delays the audio (voices too EARLY), − makes it earlier.", 12f, SUB)
                .apply { setPadding(0, dp(4), 0, dp(8)) })
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(button("−50") { changeNudge(-50) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            nudgeTv = label("", 16f, TEXT, true).apply { gravity = Gravity.CENTER }
            row.addView(nudgeTv, LinearLayout.LayoutParams(0, -2, 1.4f))
            row.addView(button("+50") { changeNudge(50) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(row)
        })
        changeNudge(0)

        box.addView(button("Choose a different audio phone") {
            if (!Rec.running) { p.edit().remove("mac").apply(); render() } else toast("Stop recording first")
        })
        box.addView(button("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        addRoleButton(box)
    }

    private fun setupScreen(root: LinearLayout) {
        header(root, "One-time setup")
        root.addView(card().apply {
            addView(label(
                "1. On BOTH phones: Android Settings → Bluetooth → pair them with each other.\n" +
                "2. Open DualRec on the audio phone and choose “AUDIO phone”.\n" +
                "3. Come back here and tap the audio phone below.", 14f, SUB
            ))
        })
        root.addView(button("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        if (required().isNotEmpty()) {
            root.addView(button("Allow Bluetooth permission", ACCENT, 0xFFFFFFFF.toInt()) { askPermissions() })
        } else {
            root.addView(label("Paired phones", 14f, SUB, true).apply { setPadding(0, dp(20), 0, 0) })
            val devs = try { Bt.adapter(this)?.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
            if (devs.isEmpty()) root.addView(label("(none yet — pair the phones first, then reopen this screen)", 13f, SUB))
            for (d in devs) {
                root.addView(button("${d.name ?: "Unknown"}   ${d.address}") {
                    p.edit().putString("mac", d.address).putString("macname", d.name ?: d.address).apply(); render()
                })
            }
        }
        root.addView(spacer(1f))
        addRoleButton(root)
    }

    private fun audioScreen(root: LinearLayout) {
        header(root, "Audio phone")
        root.addView(card().apply {
            statusTv = label("", 18f, TEXT, true)
            addView(statusTv)
            addView(label(
                "This phone records the SOUND. Place it right next to the video phone's loudspeaker " +
                "(microphone side towards the speaker). Once it says “Listening” you can lock the screen.", 13f, SUB
            ).apply { setPadding(0, dp(8), 0, 0) })
        })
        mainBtn = button("", ACCENT, 0xFFFFFFFF.toInt()) {
            if (AudioService.up) startService(Intent(this, AudioService::class.java).setAction("QUIT"))
            else if (askPermissions()) startListeningIfReady()
        }
        root.addView(mainBtn)
        root.addView(button("Keep running in background (recommended)") { allowBackground() })
        root.addView(button("Save last audio recording to phone") { saveLastAudio() })
        root.addView(button("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        root.addView(label(
            "First time: pair this phone with the video phone in Android Settings → Bluetooth.\n" +
            "On Xiaomi/POCO also turn ON Autostart for DualRec and set Battery saver → No restrictions.", 12f, SUB
        ).apply { setPadding(0, dp(12), 0, 0) })
        root.addView(spacer(1f))
        addRoleButton(root)
    }

    private fun addRoleButton(into: LinearLayout) {
        into.addView(button("Change role of this phone") {
            if (!Rec.running && !AudioService.up) { p.edit().remove("role").apply(); render() }
            else toast("Stop recording / listening first")
        }.apply { textSize = 13f })
    }

    private fun startListeningIfReady() {
        if (!AudioService.up && required().isEmpty()) {
            startForegroundService(Intent(this, AudioService::class.java))
        }
    }

    private fun tick() {
        when (role()) {
            "A" -> {
                val st = when {
                    !Rec.running -> 0
                    Rec.startedAt == 0L || Rec.saving -> 1
                    else -> 2
                }
                recBtn?.let {
                    it.state = st
                    it.contentDescription = when (st) { 0 -> "Start recording"; 1 -> "Please wait"; else -> "Stop and save" }
                }
                timerTv?.text = if (st == 2) fmt(SystemClock.elapsedRealtime() - Rec.startedAt) else " "
                statusTv?.text = if (!Rec.running && !Rec.recovering && Rec.status == "Ready") "Tap the button to start recording" else Rec.status
                btChip?.let {
                    val on = Bt.adapter(this)?.isEnabled == true
                    it.text = if (on) "● Bluetooth on" else "○ Bluetooth off"
                    it.setTextColor(if (on) GREEN else AMBER)
                    it.background = shape(0x1AFFFFFF, 20)
                }
            }
            "B" -> {
                statusTv?.text = if (AudioService.up) "✔ Listening\n${AudioService.status}" else "Not listening"
                statusTv?.setTextColor(if (AudioService.up) GREEN else TEXT)
                mainBtn?.text = if (AudioService.up) "Stop listening" else "Start listening"
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun changeNudge(d: Long) {
        val v = p.getLong("nudge", 0L) + d
        p.edit().putLong("nudge", v).apply()
        nudgeTv?.text = (if (v > 0) "+" else "") + "$v ms"
    }

    // ---------- actions ----------

    private fun recover(merge: Boolean) {
        if (Rec.recovering || Rec.running) return
        Rec.recovering = true
        Rec.status = "Recovering…"
        thread {
            RecLog.clear()
            var msg = "Nothing to recover"
            try {
                for (s in Sessions.list(this)) {
                    msg = if (merge) Recovery.finish(this, s) { Rec.status = it } else Recovery.saveRaw(this, s) { Rec.status = it }
                }
            } catch (e: Throwable) {
                msg = "Recovery problem: ${e.message}. Your files are still kept inside DualRec."
            }
            Rec.status = msg
            Rec.recovering = false
            toast(msg)
            runOnUiThread { render() }
        }
    }

    private fun saveLastAudio() {
        val f = java.io.File(cacheDir, "audio_b.m4a")
        if (!f.exists() || f.length() < 1000) { toast("No audio recording is stored on this phone"); return }
        if (AudioService.up && AudioService.status.contains("Recording", ignoreCase = true)) { toast("Wait until the recording has stopped"); return }
        toast("Saving…")
        thread {
            try {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date(f.lastModified()))
                Saver.copyAudio(this, f, "Call_${stamp}_audio.m4a")
                toast("Saved to Music/DualRec ✔")
            } catch (e: Exception) { toast("Could not save: ${e.message}") }
        }
    }
    private fun onRec() {
        when {
            Rec.recovering -> toast("Still saving the previous recording…")
            Rec.saving -> toast("Saving — one moment…")
            Rec.running -> startService(Intent(this, RecorderService::class.java).setAction("STOP"))
            else -> startActivityForResult(Intent(this, QuickStartActivity::class.java), 7)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        // recording started -> get out of the way so the call app is on screen
        if (rc == 7 && res == RESULT_OK) moveTaskToBack(true)
    }

    private fun pinWidget() {
        val m = getSystemService(AppWidgetManager::class.java)
        if (m != null && m.isRequestPinAppWidgetSupported) {
            m.requestPinAppWidget(ComponentName(this, RecWidget::class.java), null, null)
        } else {
            toast("Long-press the home screen → Widgets → Dual Rec")
        }
    }

    private fun allowBackground() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already allowed ✔"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun testConnection() {
        val mac = p.getString("mac", "") ?: ""
        toast("Testing…")
        thread {
            try {
                val l = Bt.connect(this, mac)
                val t0 = System.currentTimeMillis()
                l.time()
                val ms = System.currentTimeMillis() - t0
                l.close()
                toast("Connected ✔  (Bluetooth delay $ms ms)")
            } catch (e: Exception) {
                toast("Not connected: ${e.message}")
            }
        }
    }
}