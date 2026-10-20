package com.dualrec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible one-tap entry point used by the widget, the Quick Settings tile and the big button.
 * Running  -> stops & saves.
 * Idle     -> Bluetooth permission -> Bluetooth on -> Android's "Start now" -> recording service.
 */
@SuppressLint("MissingPermission")
class QuickStartActivity : Activity() {
    private var askedPerm = false
    private var askedBt = false
    private var askedCapture = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = getSharedPreferences("d", MODE_PRIVATE)
        if (Rec.running) {
            startService(Intent(this, RecorderService::class.java).setAction("STOP"))
            finish()
            return
        }
        if (Rec.recovering) {
            Toast.makeText(this, "Still saving the previous recording…", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val ready = p.getString("role", "") == "A" && !p.getString("mac", "").isNullOrEmpty()
        if (!ready) {
            Toast.makeText(this, "Finish the one-time setup in DualRec first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        proceed()
    }

    private fun proceed() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            if (askedPerm) { bail("Bluetooth permission is needed"); return }
            askedPerm = true
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
            return
        }
        val a = Bt.adapter(this)
        if (a == null) { bail("This phone has no Bluetooth"); return }
        if (!a.isEnabled && !askedBt) {
            askedBt = true
            // Silent switch-on if Android allows it; otherwise the system asks once ("Allow DualRec to turn on Bluetooth?").
            if (!Bt.tryEnable(this)) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8)
                return
            }
        }
        if (!askedCapture) {
            askedCapture = true
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val i = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            startActivityForResult(i, 7)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        proceed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        when (rc) {
            8 -> if (res == RESULT_OK) proceed() else bail("Bluetooth is needed to reach the audio phone")
            7 -> if (res == RESULT_OK && d != null) {
                startForegroundService(
                    Intent(this, RecorderService::class.java).setAction("START").putExtra("code", res).putExtra("data", d)
                )
                setResult(RESULT_OK)
                finish()
            } else bail(null)
        }
    }

    private fun bail(msg: String?) {
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }
}