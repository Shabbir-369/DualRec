package com.dualrec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private lateinit var p: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var statusTv: TextView? = null
    private var mainBtn: Button? = null
    private var nudgeTv: TextView? = null

    private fun role() = p.getString("role", "") ?: ""
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, size: Float = 16f) =
        TextView(this).apply { text = t; textSize = size; setPadding(0, dp(10), 0, dp(6)) }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private val ticker = object : Runnable {
        override fun run() { tick(); ui.postDelayed(this, 700) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        p = getSharedPreferences("d", MODE_PRIVATE)
        render()
        auto(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); auto(intent) }

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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(24)) }
        root.addView(tv("DualRec", 30f).apply { setTypeface(typeface, Typeface.BOLD) })
        statusTv = null; mainBtn = null; nudgeTv = null
        when (role()) {
            "A" -> videoScreen(root)
            "B" -> audioScreen(root)
            else -> {
                root.addView(tv("Which phone is this?\n(You only choose this once on each phone.)"))
                root.addView(btn("📱  VIDEO phone  (the one on the call)") { p.edit().putString("role", "A").apply(); askPermissions(); render() })
                root.addView(btn("🎙  AUDIO phone  (placed next to the speaker)") { p.edit().putString("role", "B").apply(); askPermissions(); render() })
            }
        }
        if (role() != "") {
            root.addView(btn("Change role of this phone") {
                if (!Rec.running && !AudioService.up) { p.edit().remove("role").apply(); render() }
                else toast("Stop recording / listening first")
            }.apply { textSize = 12f })
        }
        setContentView(ScrollView(this).apply { addView(root); fitsSystemWindows = true })
        tick()
    }

    private fun videoScreen(root: LinearLayout) {
        val mac = p.getString("mac", "") ?: ""
        if (mac.isEmpty()) {
            root.addView(tv("ONE-TIME SETUP", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
            root.addView(tv("1. On BOTH phones: Android Settings → Bluetooth → pair them with each other.\n2. Open DualRec on the audio phone and choose 'AUDIO phone'.\n3. Come back here and tap the audio phone in the list below."))
            root.addView(btn("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
            if (!askPermissionsQuiet()) {
                root.addView(btn("Allow Bluetooth permission") { askPermissions() })
            } else {
                root.addView(tv("Paired phones:"))
                val devs = try { Bt.adapter(this)?.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
                if (devs.isEmpty()) root.addView(tv("(none yet — pair the phones first, then reopen this screen)"))
                for (d in devs) {
                    root.addView(btn("${d.name ?: "Unknown"}   ${d.address}") {
                        p.edit().putString("mac", d.address).putString("macname", d.name ?: d.address).apply(); render()
                    })
                }
            }
        } else {
            root.addView(tv("Audio phone: ${p.getString("macname", mac)}"))
            mainBtn = btn("●  RECORD") { onRec() }.apply { textSize = 26f; setPadding(dp(8), dp(36), dp(8), dp(36)) }
            root.addView(mainBtn)
            statusTv = tv("Ready").apply { gravity = Gravity.CENTER }
            root.addView(statusTv)
            root.addView(tv("Tip: add the “Dual Rec” tile to Quick Settings for one-tap start/stop while you are in WhatsApp.", 13f))

            root.addView(tv("Fine-tune sync (only if voices sound late/early in a test video):", 13f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(btn("−50") { changeNudge(-50) })
            nudgeTv = tv("", 16f).apply { setPadding(dp(16), 0, dp(16), 0) }
            row.addView(nudgeTv)
            row.addView(btn("+50") { changeNudge(50) })
            root.addView(row)
            changeNudge(0)
            root.addView(tv("+ delays the audio (use if voices come too EARLY). − makes it earlier.", 12f))

            root.addView(btn("Test Bluetooth connection") { testConnection() })
            root.addView(btn("Choose a different audio phone") {
                if (!Rec.running) { p.edit().remove("mac").apply(); render() }
            })
        }
    }

    private fun askPermissionsQuiet() = required().isEmpty()

    private fun audioScreen(root: LinearLayout) {
        root.addView(tv("This phone records the SOUND.\n\nPlace it right next to the video phone's loudspeaker (microphone side towards the speaker). Once it says “Listening” you can lock the screen — leave it there."))
        statusTv = tv("").apply { textSize = 18f }
        root.addView(statusTv)
        mainBtn = btn("") {
            if (AudioService.up) startService(Intent(this, AudioService::class.java).setAction("QUIT")) else {
                if (askPermissions()) startListeningIfReady()
            }
        }
        root.addView(mainBtn)
        root.addView(tv("First time: pair this phone with the video phone in Android Settings → Bluetooth.", 13f))
        root.addView(btn("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
    }

    private fun startListeningIfReady() {
        if (!AudioService.up && required().isEmpty()) {
            startForegroundService(Intent(this, AudioService::class.java))
        }
    }

    private fun tick() {
        if (role() == "A") {
            statusTv?.text = Rec.status
            mainBtn?.text = if (Rec.running) "■  STOP & SAVE" else "●  RECORD"
        } else if (role() == "B") {
            statusTv?.text = if (AudioService.up) "✔ Listening\n${AudioService.status}" else "Not listening"
            mainBtn?.text = if (AudioService.up) "Stop listening" else "Start listening"
        }
    }

    private fun changeNudge(d: Long) {
        val v = p.getLong("nudge", 0L) + d
        p.edit().putLong("nudge", v).apply()
        nudgeTv?.text = (if (v > 0) "+" else "") + "$v ms"
    }

    // ---------- actions (video phone) ----------
    private fun onRec() {
        if (Rec.running) {
            startService(Intent(this, RecorderService::class.java).setAction("STOP"))
            return
        }
        if (!askPermissions()) { toast("Allow the Bluetooth permission, then tap Record again"); return }
        beginRecord()
    }

    private fun beginRecord() {
        Rec.status = "Waiting for screen-capture permission…"
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 7)
    }

    private fun auto(i: Intent?) {
        if (i?.getBooleanExtra("autostart", false) != true) return
        i.removeExtra("autostart")
        if (role() == "A" && !Rec.running && !p.getString("mac", "").isNullOrEmpty() && required().isEmpty()) beginRecord()
        else toast("Finish the setup in DualRec first")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc == 7) {
            if (res == RESULT_OK && d != null) {
                startForegroundService(
                    Intent(this, RecorderService::class.java).setAction("START").putExtra("code", res).putExtra("data", d)
                )
                moveTaskToBack(true)
            } else Rec.status = "Ready"
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
