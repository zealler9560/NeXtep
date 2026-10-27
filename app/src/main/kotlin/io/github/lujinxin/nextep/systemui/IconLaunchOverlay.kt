package io.github.lujinxin.nextep.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
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
import android.view.animation.LinearInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * The launcher's own open animation lifts the tapped icon on a plate and grows it into the window.
 * The task itself is black until the app draws, so this plate flies in a SystemUI window over the
 * task's place and fades away once the task is presented under it. The look follows an
 * [IconLaunchStyle]; the mechanics are the same on every ROM.
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

    /** [icon] flies from [from] to [to] on a plate of [plateColor], the way [style] says. */
    fun start(icon: Drawable, plateColor: Int, from: Rect, to: Rect, style: IconLaunchStyle) {
        prepare()
        val view = flight ?: return
        val mine = ++generation
        view.begin(icon, plateColor, from, to, style)
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
        private var style = IconLaunchStyle.GENERIC
        private val from = Rect()
        private val to = Rect()
        private val pop = RectF()
        /** Elapsed share of the whole flight, 0..1, time-linear; the phases read it. */
        private var progress = 0f
        private var flying: ValueAnimator? = null
        private var onLanded: (() -> Unit)? = null

        fun begin(icon: Drawable, plateColor: Int, from: Rect, to: Rect, style: IconLaunchStyle) {
            rest()
            this.icon = icon
            this.style = style
            this.from.set(from)
            this.to.set(to)
            plate.color = plateColor
            if (style.popFraction > 0f) {
                val w = to.width() * style.popFraction
                val h = to.height() * style.popFraction
                val cx = from.exactCenterX() + (to.exactCenterX() - from.exactCenterX()) * style.popBias
                val cy = from.exactCenterY() + (to.exactCenterY() - from.exactCenterY()) * style.popBias
                pop.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            } else {
                pop.set(from)
                val grow = (style.liftScale - 1f) / 2f
                pop.inset(-from.width() * grow, -from.height() * grow)
            }
            progress = 0f
            alpha = 1f
            visibility = VISIBLE
            flying = animator(0f, 1f, style.totalMs).also { it.start() }
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
            interpolator = LinearInterpolator()
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
            val elapsed = progress * style.totalMs
            val icon = icon
            if (elapsed < style.liftMs) {
                // The icon lifts in place: it is drawn as itself, growing, on its own plate.
                val scale = 1f + (style.liftScale - 1f) * (elapsed / style.liftMs)
                val halfW = from.width() * scale / 2
                val halfH = from.height() * scale / 2
                bounds.set(from.exactCenterX() - halfW, from.exactCenterY() - halfH, from.exactCenterX() + halfW, from.exactCenterY() + halfH)
                val radius = bounds.width() * ICON_CORNER
                canvas.drawRoundRect(bounds, radius, radius, plate)
                drawIcon(canvas, icon, bounds.width(), 1f)
                return
            }
            val g = style.growCurve.getInterpolation(((elapsed - style.liftMs) / style.growMs).coerceIn(0f, 1f))
            bounds.set(lerp(pop.left, to.left.toFloat(), g), lerp(pop.top, to.top.toFloat(), g),
                lerp(pop.right, to.right.toFloat(), g), lerp(pop.bottom, to.bottom.toFloat(), g))
            val radius = lerp(from.width() * ICON_CORNER * style.liftScale, PANE_CORNER_DP * density, g)
            canvas.drawRoundRect(bounds, radius, radius, plate)
            val iconAlpha = 1f - ((g - style.iconFadeStart) / (style.iconFadeEnd - style.iconFadeStart)).coerceIn(0f, 1f)
            if (iconAlpha <= 0f) return
            val lifted = from.width() * style.liftScale
            val size = if (style.iconGrows) lerp(lifted, to.width() * ICON_END_FRACTION, g) else lifted
            drawIcon(canvas, icon, size, iconAlpha)
        }

        private fun drawIcon(canvas: Canvas, icon: Drawable?, size: Float, alpha: Float) {
            icon ?: return
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            icon.setBounds(Math.round(cx - size / 2), Math.round(cy - size / 2), Math.round(cx + size / 2), Math.round(cy + size / 2))
            icon.alpha = Math.round(alpha * 255)
            icon.draw(canvas)
            icon.alpha = 255
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }

    companion object {
        private const val FINISH_MS = 160L
        private const val FADE_MS = 150L
        private const val TIMEOUT_MS = 1_500L
        private const val ICON_CORNER = 0.22f
        private const val PANE_CORNER_DP = 14f
        private const val ICON_END_FRACTION = 0.6f
    }
}
