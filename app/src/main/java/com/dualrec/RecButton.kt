package com.dualrec

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/** The big round button. state: 0 = idle (REC), 1 = busy (connecting / saving), 2 = recording (STOP, pulsing). */
class RecButton(ctx: Context) : View(ctx) {
    var state = 0
        set(v) {
            if (field != v) { field = v; syncAnim(); invalidate() }
        }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFB020.toInt()
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val box = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    private fun syncAnim() {
        if (state == 0) anim.cancel() else if (!anim.isRunning) anim.start()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnim() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 8f * d
        when (state) {
            0 -> {
                ring.color = 0x55FFFFFF
                c.drawCircle(cx, cy, r, ring)
                fill.color = 0xFFFF3B4E.toInt()
                c.drawCircle(cx, cy, r * 0.8f, fill)
                txt.textSize = r * 0.30f
                c.drawText("REC", cx, cy + txt.textSize * 0.35f, txt)
            }
            1 -> {
                ring.color = 0x33FFFFFF
                c.drawCircle(cx, cy, r, ring)
                fill.color = 0xFF2A2E39.toInt()
                c.drawCircle(cx, cy, r * 0.8f, fill)
                box.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(box, t * 360f, 100f, false, arc)
                txt.textSize = r * 0.17f
                c.drawText("PLEASE WAIT", cx, cy + txt.textSize * 0.35f, txt)
            }
            else -> {
                glow.color = Color.argb((80 * (1f - t)).toInt(), 255, 59, 78)
                c.drawCircle(cx, cy, r * (0.82f + 0.18f * t), glow)
                ring.color = 0xAAFF3B4E.toInt()
                c.drawCircle(cx, cy, r, ring)
                fill.color = 0xFFFF3B4E.toInt()
                c.drawCircle(cx, cy, r * 0.8f, fill)
                fill.color = Color.WHITE
                val h = r * 0.26f
                box.set(cx - h, cy - h, cx + h, cy + h)
                c.drawRoundRect(box, h * 0.3f, h * 0.3f, fill)
            }
        }
    }
}
