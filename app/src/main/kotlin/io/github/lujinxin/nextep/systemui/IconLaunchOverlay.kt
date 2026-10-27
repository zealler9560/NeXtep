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
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * The launcher's own open animation lifts the tapped icon on a plate of its colour and grows it
 * into the window. The task itself is black until the app draws, so this plate flies in a SystemUI
 * window over the task's place and fades away once the task is presented under it.
 *
 * The window is added hidden ahead of time ([prepare]): creating it on the tap cost the main
 * thread the add and the window's first layout, about 50 ms, right when the flight should start.
 */
class IconLaunchOverlay(private val context: Context, private val windowType: () -> Int?) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var flight: FlightView? = null
    private var generation = 0

    fun prepare() {
        if (flight != null) return
        val type = windowType() ?: return
        val view = FlightView(context).apply { visibility = View.INVISIBLE }
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
        runCatching { windowManager.addView(view, params) }
            .onSuccess { flight = view }
            .onFailure { NeXtepLog.warn("icon_launch", "Overlay window unavailable", it) }
    }

    fun release() {
        val view = flight ?: return
        flight = null
        generation++
        runCatching { windowManager.removeView(view) }
    }

    fun start(icon: Drawable, from: Rect, to: Rect) {
        prepare()
        val view = flight ?: return
        val mine = ++generation
        view.begin(icon, from, to)
        // An app that never shows up must not leave its plate on screen.
        handler.postDelayed({ if (generation == mine) finish() }, TIMEOUT_MS)
    }

    /** The task is in place under the plate: the flight finishes quickly and the plate fades out. */
    fun finish() {
        val view = flight?.takeIf { it.visibility == View.VISIBLE } ?: return
        val mine = ++generation
        view.land { if (generation == mine) view.rest() }
    }

    fun cancel() {
        generation++
        flight?.rest()
    }

    private class FlightView(context: Context) : View(context) {
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private val density = context.resources.displayMetrics.density
        private var icon: Drawable? = null
        private val from = Rect()
        private val to = Rect()
        private var progress = 0f
        private var flying: ValueAnimator? = null
        private var onLanded: (() -> Unit)? = null

        fun begin(icon: Drawable, from: Rect, to: Rect) {
            rest()
            this.icon = icon
            this.from.set(from)
            this.to.set(to)
            plate.color = plateColor(icon)
            progress = 0f
            alpha = 1f
            visibility = VISIBLE
            flying = animator(0f, 1f, FLIGHT_MS).also { it.start() }
        }

        fun land(onRested: () -> Unit) {
            val fade = { animate().alpha(0f).setDuration(FADE_MS).withEndAction(onRested).start() }
            val current = flying
            if (current == null) { fade(); return }
            // The task is ready: the rest of the way is covered quickly, then the plate fades.
            onLanded = fade
            flying = null
            current.cancel()
            flying = animator(progress, 1f, ((1f - progress) * FINISH_MS).toLong().coerceAtLeast(60L)).also { it.start() }
        }

        fun rest() {
            onLanded = null
            flying?.let { flying = null; it.cancel() }
            animate().cancel()
            visibility = INVISIBLE
        }

        private fun animator(start: Float, end: Float, durationMs: Long) = ValueAnimator.ofFloat(start, end).apply {
            duration = durationMs
            interpolator = FLIGHT_CURVE
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (flying !== animation) return
                    flying = null
                    onLanded?.also { onLanded = null }?.invoke()
                }
            })
        }

        override fun onDraw(canvas: Canvas) {
            val t = progress
            bounds.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t), lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t))
            val radius = lerp(from.width() * ICON_CORNER, PANE_CORNER_DP * density, t)
            canvas.drawRoundRect(bounds, radius, radius, plate)
            // The icon grows with the plate for most of the flight and is gone as it lands.
            val icon = icon ?: return
            val iconAlpha = 1f - ((t - ICON_FADE_START) / (ICON_FADE_END - ICON_FADE_START)).coerceIn(0f, 1f)
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
        const val FLIGHT_MS = 400L
        const val FINISH_MS = 160L
        const val FADE_MS = 150L
        const val TIMEOUT_MS = 1_500L
        const val ICON_CORNER = 0.22f
        const val PANE_CORNER_DP = 14f
        const val ICON_FADE_START = 0.6f
        const val ICON_FADE_END = 0.95f
        const val ICON_END_FRACTION = 0.6f
        val FLIGHT_CURVE = PathInterpolator(0.2f, 0f, 0f, 1f)

        /**
         * An adaptive icon's background layer, the colour the launcher grows out of; otherwise the
         * icon as a whole. The average of the opaque pixels of a 16x16 rendering.
         */
        fun plateColor(icon: Drawable): Int = runCatching {
            val layer = (icon as? AdaptiveIconDrawable)?.background ?: icon
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            layer.setBounds(0, 0, 16, 16)
            layer.draw(Canvas(bitmap))
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
