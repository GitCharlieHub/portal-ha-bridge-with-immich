package com.aeonos.portalha

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.sin

/**
 * Echo-style "Alexa is listening" cue: a glowing cyan→blue bar along the bottom edge with a
 * soft upward glow and a horizontal shimmer that flows side to side (the signature Alexa
 * motion). Shown during an Alexa turn — it sits ABOVE the wake cover, so the user sees their
 * frozen dashboard with the listening bar on top, exactly the "speak now" cue the hidden
 * falcon UI would otherwise provide. Non-touchable overlay; [hide] fades it out.
 */
class AlexaBarOverlay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)
    @Volatile private var view: BarView? = null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            if (view != null) return@post
            runCatching {
                val v = BarView(context)
                val h = (context.resources.displayMetrics.density * 140).toInt()   // glow height
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.BOTTOM }
                view = v
                v.alpha = 0f
                wm.addView(v, lp)
                v.animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            view = null
            v.animate().alpha(0f).setDuration(200)
                .withEndAction { runCatching { wm.removeView(v) } }.start()
        }
    }

    private class BarView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val t0 = System.nanoTime()
        // Echo palette: cyan ↔ blue.
        private val cyan = Color.rgb(0, 210, 255)
        private val blue = Color.rgb(40, 90, 255)
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { postInvalidateOnAnimation() }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w < 2f) return
            val t = (System.nanoTime() - t0) / 1e9f
            val barH = density * 6f
            val breathe = 0.75f + 0.25f * (0.5f + 0.5f * sin(t * 2.0f))   // gentle pulse

            // Flowing horizontal shimmer: a cyan→blue→cyan gradient translated over time.
            val shift = (sin(t * 0.9f) * 0.5f + 0.5f) * w
            val flow = LinearGradient(
                -w + shift, 0f, w + shift, 0f,
                intArrayOf(cyan, blue, cyan, blue, cyan),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.MIRROR
            )

            // Soft upward glow (bar colour at the bottom → transparent going up), pulsing.
            glow.shader = LinearGradient(
                0f, h, 0f, h - density * 120f,
                intArrayOf(
                    withA(cyan, (150 * breathe).toInt()),
                    withA(blue, (55 * breathe).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            // Tint the glow with the flowing shimmer.
            canvas.drawRect(0f, h - density * 120f, w, h, glow)

            // The bright bar itself, riding the flow gradient.
            bar.shader = flow
            rect.set(0f, h - barH, w, h)
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, bar)
        }

        private fun withA(c: Int, a: Int) =
            Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    }
}
