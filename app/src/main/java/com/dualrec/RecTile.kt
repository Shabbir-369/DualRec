package com.dualrec

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap to start (then confirm Android's "Start now"), one tap to stop & save. */
class RecTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        if (Rec.running) {
            startService(Intent(this, RecorderService::class.java).setAction("STOP"))
        } else {
            val i = Intent(this, MainActivity::class.java)
                .putExtra("autostart", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Rec.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (Rec.running) "Stop & save" else "Dual Rec"
        t.updateTile()
    }
}
