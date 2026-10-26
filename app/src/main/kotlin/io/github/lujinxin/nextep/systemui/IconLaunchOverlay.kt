package io.github.lujinxin.nextep.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * The launcher's own open animation lifts the tapped icon on a plate of its colour and grows it
 * into the window. The task itself is black until the app draws, so this plate flies in a SystemUI
 * window over the task's place and fades away once the task is presented under it.
 */
class IconLaunchOverlay(private val context: Context, private val windowType: () -> Int?) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var flight: FlightView? = null
    private var generation = 0

    fun start(icon: Drawable, from: Rect, to: Rect) {
        cancel()
        val type = windowType() ?: return
        val view = FlightView(context, icon, from, to)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "NeXtepIconLaunch"
        }
        runCatching { windowManager.addView(view, params) }.onFailure {
            NeXtepLog.warn("icon_launch", "Overlay window unavailable", it)
            return
        }
        flight = view
        val mine = ++generation
        view.fly()
        // An app that never shows up must not leave its plate on screen.
        handler.postDelayed({ if (generation == mine) finish() }, TIMEOUT_MS)
    }

    /** The task is in place under the plate: once the flight has landed, the plate fades out. */
    fun finish() {
        val view = flight ?: return
        flight = null
        generation++
        view.land { runCatching { windowManager.removeView(view) } }
    }

    fun cancel() {
        val view = flight ?: return
        flight = null
        generation++
        runCatching { windowManager.removeView(view) }
    }

    private class FlightView(context: Context, private val icon: Drawable, private val from: Rect, private val to: Rect) :
        View(context) {
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dominantColor(icon) }
        private val bounds = RectF()
        private val density = context.resources.displayMetrics.density
        private var progress = 0f
        private var flying: ValueAnimator? = null
        private var onLanded: (() -> Unit)? = null

        fun fly() {
            flying = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FLIGHT_MS
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        flying = null
                        onLanded?.invoke()
                    }
                })
                start()
            }
        }

        fun land(onRemoved: () -> Unit) {
            val fade = { animate().alpha(0f).setDuration(FADE_MS).withEndAction(onRemoved).start() }
            if (flying != null) onLanded = fade else fade()
        }

        override fun onDraw(canvas: Canvas) {
            val t = progress
            bounds.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t), lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t))
            val radius = lerp(from.width() * ICON_CORNER, PANE_CORNER_DP * density, t)
            canvas.drawRoundRect(bounds, radius, radius, plate)
            // The icon grows with the plate and has faded by the time the plate is half way.
            val iconAlpha = 1f - (t / ICON_FADE_END).coerceAtMost(1f)
            if (iconAlpha <= 0f) return
            val size = lerp(from.width().toFloat(), to.width() * ICON_END_FRACTION, t)
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            icon.setBounds(Math.round(cx - size / 2), Math.round(cy - size / 2), Math.round(cx + size / 2), Math.round(cy + size / 2))
            icon.alpha = Math.round(iconAlpha * 255)
            icon.draw(canvas)
            icon.alpha = 255
        }

        private fun lerp(a: Int, b: Int, t: Float) = a + (b - a) * t
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }

    private companion object {
        const val FLIGHT_MS = 360L
        const val FADE_MS = 150L
        const val TIMEOUT_MS = 1_500L
        const val ICON_CORNER = 0.22f
        const val PANE_CORNER_DP = 14f
        const val ICON_FADE_END = 0.55f
        const val ICON_END_FRACTION = 0.5f

        /** The average of the icon's opaque pixels, which for an adaptive icon is mostly its background. */
        fun dominantColor(icon: Drawable): Int = runCatching {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 16, 16)
            icon.draw(Canvas(bitmap))
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in 0 until 16) for (x in 0 until 16) {
                val c = bitmap.getPixel(x, y)
                if (Color.alpha(c) < 128) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
            bitmap.recycle()
            if (n == 0L) Color.WHITE else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }.getOrDefault(Color.WHITE)
    }
}
