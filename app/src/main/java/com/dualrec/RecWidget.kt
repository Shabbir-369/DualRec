package com.dualrec

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Home-screen widget: one tap starts (Bluetooth on -> connect -> record) or stops & saves. */
class RecWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_rec)
            val running = Rec.running
            val timing = running && Rec.startedAt > 0 && !Rec.saving
            v.setInt(R.id.w_root, "setBackgroundResource", if (running) R.drawable.widget_bg_rec else R.drawable.widget_bg_idle)
            v.setImageViewResource(R.id.w_icon, if (running) R.drawable.widget_icon_rec else R.drawable.widget_icon_idle)
            v.setTextViewText(R.id.w_title, when {
                Rec.saving -> "Saving…"
                running && timing -> "Tap to stop"
                running -> "Starting…"
                else -> "Dual Rec"
            })
            v.setTextViewText(R.id.w_sub, when {
                Rec.saving -> "Please wait"
                timing -> "Recording"
                running -> "Connecting"
                else -> "Tap to record"
            })
            v.setViewVisibility(R.id.w_timer, if (timing) View.VISIBLE else View.GONE)
            if (timing) v.setChronometer(R.id.w_timer, Rec.startedAt, null, true)

            val i = Intent(ctx, QuickStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, 100, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_root, pi)
            return v
        }

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RecWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx))
        }
    }
}
