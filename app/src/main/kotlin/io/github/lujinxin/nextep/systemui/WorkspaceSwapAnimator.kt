package io.github.lujinxin.nextep.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import io.github.lujinxin.nextep.logging.NeXtepLog

/** Animate disposable previews first; perform the display move once the final preview is on screen
 * and covers relayout, then cut to the live buffers in one frame. Nothing retains an app frame after
 * the transition ends or the workspace is hidden/locked. */
internal class WorkspaceSwapAnimator(
    private val context: Context,
    /** The panels' frosted wallpaper over the whole screen; null until it has been captured. */
    private val backdrop: () -> android.graphics.drawable.Drawable? = { null },
    private val appearance: () -> WorkbenchAppearance,
) {
    /** A frame without a bitmap draws as a card; an empty one also carries the plus badge.
     * A [topFill] frame fills its width from the top, so a portrait page shows its player in a wide pane. */
    data class Frame(val bitmap: Bitmap?, val from: RectF, var to: RectF, val empty: Boolean = false,
                     val topFill: Boolean = false, val endBitmap: Bitmap? = null)
    private val manager = context.getSystemService(WindowManager::class.java)
    /** Added once and kept hidden between exchanges: adding and removing a window per exchange made
     * its first and last frames land a frame apart from the panels' changes, which showed as a flash. */
    private var overlay: SwapPreviewView? = null
    private var showing = false
    private var animator: ValueAnimator? = null
    private var generation = 0
    private var frozen = false
    fun animate(
        source: Bitmap?, target: Bitmap?, targetEmpty: Boolean,
        start: RectF, sourceBounds: RectF, targetBounds: RectF,
        move: (() -> Unit) -> Unit,
    ) = animateFrames(listOf(
        // The vacated card travels back as well, so no bare panel shows where it lands.
        Frame(target, targetBounds, sourceBounds, empty = targetEmpty),
        Frame(source, start, targetBounds),
    ), listOf(sourceBounds, targetBounds), move)

    fun animateFrames(frames: List<Frame>, covers: List<RectF>, move: (() -> Unit) -> Unit) {
        if (show(frames, covers)) animateOverlay(move) else move {}
    }

    /** Hold [frames] at their start positions over the live windows until [play]. */
    fun freeze(frames: List<Frame>, covers: List<RectF>): Boolean = show(frames, covers).also { frozen = it }

    /** Runs [action] once, when the overlay's first frame is on screen (at once without an overlay). */
    fun whenShown(action: () -> Unit) {
        val preview = overlay?.takeIf { showing } ?: return action()
        var done = false
        fun once() {
            if (!done) { done = true; action() }
        }
        preview.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                preview.viewTreeObserver.removeOnPreDrawListener(this)
                // A window turning visible shows a frame after its commit and the window manager's next pass;
                // two vsyncs after the draw were once one short, and the layout change showed first.
                val choreographer = android.view.Choreographer.getInstance()
                preview.viewTreeObserver.registerFrameCommitCallback {
                    choreographer.postFrameCallback { choreographer.postFrameCallback { once() } }
                }
                return true
            }
        })
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(::once, SHOW_TIMEOUT_MS)
    }

    /** Identifies the running animation; a hand-over meant for an earlier one is ignored. */
    val current: Int get() = generation

    /** Hold one more still frame over the frozen ones, such as a card laid out after the freeze. */
    fun holdAlso(frame: Frame, behind: Boolean = false) {
        val preview = overlay?.takeIf { frozen } ?: run {
            listOfNotNull(frame.bitmap, frame.endBitmap).distinct().forEach { it.recycle() }
            return
        }
        preview.frames = if (behind) listOf(frame) + preview.frames else preview.frames + frame
        preview.invalidate()
    }

    /** Move the frozen frames to [targets] behind [covers]; [move] gets the cut to the live windows. */
    fun play(targets: List<RectF>, covers: List<RectF>, replace: Map<Int, Bitmap?> = emptyMap(),
             move: (() -> Unit) -> Unit = { reveal -> reveal() }) {
        val preview = overlay?.takeIf { frozen } ?: run {
            replace.values.filterNotNull().distinct().forEach { it.recycle() }
            return move {}
        }
        frozen = false
        // Keep the frozen picture at progress zero; the relaid-out page arrives during the flight.
        preview.frames = preview.frames.mapIndexed { index, frame ->
            val fresh = replace[index] ?: return@mapIndexed frame
            frame.copy(endBitmap = fresh)
        }
        preview.frames.zip(targets).forEach { (frame, target) -> frame.to = target }
        preview.covers = covers
        animateOverlay(move)
    }

    /** Adds the overlay window, hidden, ahead of the first exchange. */
    fun prepare() {
        if (overlay != null) return
        val windowType = SystemUiWindowTypeResolver.resolve() ?: return
        val preview = SwapPreviewView(context, emptyList(), emptyList(), appearance()).apply { visibility = View.INVISIBLE }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            title = "NeXtepSwapPreview"
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { manager.addView(preview, params) }
            .onSuccess { overlay = preview }
            .onFailure { NeXtepLog.warn("slot_drag", "Swap overlay window unavailable", it) }
    }

    fun release() {
        cancel()
        val preview = overlay ?: return
        overlay = null
        runCatching { manager.removeViewImmediate(preview) }
    }

    private fun show(frames: List<Frame>, covers: List<RectF>): Boolean {
        cancel()
        prepare()
        val preview = overlay
        if (preview == null) {
            frames.flatMap { listOfNotNull(it.bitmap, it.endBitmap) }.distinct().forEach { it.recycle() }
            return false
        }
        preview.bind(frames, covers, appearance(), backdrop())
        preview.alpha = 1f
        preview.visibility = View.VISIBLE
        showing = true
        return true
    }

    private fun animateOverlay(move: (() -> Unit) -> Unit) {
        val preview = overlay?.takeIf { showing } ?: return move {}
        val token = ++generation
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWAP_MS
            interpolator = PathInterpolator(0.22f, 0f, 0.12f, 1f)
            addUpdateListener {
                preview.progress = it.animatedValue as Float
                preview.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != token) return
                    animator = null
                    preview.progress = 1f
                    // The final frame must be on screen before the move: the move's relayout would
                    // otherwise show between the last committed frame and the one that covers it.
                    afterCommit(preview) {
                        if (generation != token) return@afterCommit
                        runCatching {
                            move {
                                // Cut on the next frame; a fade showed the live windows still settling under it.
                                preview.postOnAnimation { if (generation == token) this@WorkspaceSwapAnimator.cancel() }
                            }
                        }.onFailure {
                            NeXtepLog.warn("slot_drag", "Exchange callback failed", it)
                            this@WorkspaceSwapAnimator.cancel()
                        }
                    }
                }
            })
            start()
        }
    }

    private fun afterCommit(preview: View, action: () -> Unit) {
        var done = false
        val once = Runnable { if (!done) { done = true; action() } }
        preview.viewTreeObserver.registerFrameCommitCallback { preview.post(once) }
        preview.invalidate()
        // A window that draws nothing (screen turning off) never commits; the move must still run.
        preview.postDelayed(once, COMMIT_TIMEOUT_MS)
    }

    /** Fade the frames held by [freeze] into the live windows beneath, where they are. */
    fun fadeFrozen() {
        val preview = overlay?.takeIf { frozen } ?: return
        frozen = false
        val token = ++generation
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 120L
            addUpdateListener { preview.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation == token) {
                        animator = null
                        this@WorkspaceSwapAnimator.cancel()
                    }
                }
            })
            start()
        }
    }

    /** Drop frames held by [freeze] that will not play; a running exchange keeps its overlay. */
    fun dismissFrozen() {
        if (frozen) cancel()
    }

    fun cancel() {
        generation += 1
        frozen = false
        animator?.cancel()
        animator = null
        if (!showing) return
        showing = false
        overlay?.apply {
            animate().cancel()
            visibility = View.INVISIBLE
            clear()
        }
    }

    private companion object {
        /** A frozen overlay that never draws must not hold up the transition behind it. */
        const val SHOW_TIMEOUT_MS = 250L
        const val SWAP_MS = 300L
        const val COMMIT_TIMEOUT_MS = 100L
    }
}

internal class SwapPreviewView(
    context: Context,
    var frames: List<WorkspaceSwapAnimator.Frame>,
    var covers: List<RectF>,
    appearance: WorkbenchAppearance,
    private var backdrop: android.graphics.drawable.Drawable? = null,
) : View(context) {
    var progress = 0f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Covers mimic the panel behind moving cards; cards mimic a live slot, so the
    // cut to the real windows changes nothing visible in either theme.
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyCardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private var badge = appearance.emptyBadge(resources.displayMetrics.density)
    private val badgeSize = (WorkbenchAppearance.BADGE_SIZE_DP * resources.displayMetrics.density).toInt()
    private val destination = RectF()
    private val imageDestination = RectF()
    private val sourceRect = Rect()
    private val clipPath = android.graphics.Path()
    private val cornerRadius = resources.displayMetrics.density * 14f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        paint(appearance)
    }

    private fun paint(appearance: WorkbenchAppearance) {
        coverPaint.color = appearance.opaquePanel
        cardPaint.color = appearance.card
        emptyCardPaint.color = appearance.slotCard
        borderPaint.color = appearance.outline
        badge = appearance.emptyBadge(resources.displayMetrics.density)
    }

    /** Show [frames] at their start, for an exchange; the view keeps the bitmaps until [clear]. */
    fun bind(frames: List<WorkspaceSwapAnimator.Frame>, covers: List<RectF>, appearance: WorkbenchAppearance,
             backdrop: android.graphics.drawable.Drawable?) {
        clear()
        this.frames = frames
        this.covers = covers
        this.backdrop = backdrop
        paint(appearance)
        progress = 0f
        invalidate()
    }

    /** Release every app frame; the view draws nothing until the next [bind]. */
    fun clear() {
        frames.flatMap { listOfNotNull(it.bitmap, it.endBitmap) }.distinct().forEach { it.recycle() }
        frames = emptyList()
        covers = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        // The live tasks stay put until the compositor move. Cover their old
        // positions so a shrinking preview cannot expose a second stationary app.
        val backdrop = backdrop
        covers.forEach { cover ->
            if (backdrop == null) return@forEach canvas.drawRoundRect(cover, cornerRadius, cornerRadius, coverPaint)
            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(cover, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
            canvas.clipPath(clipPath)
            backdrop.setBounds(0, 0, width, height)
            backdrop.draw(canvas)
            canvas.restoreToCount(save)
        }
        frames.forEach { drawFrame(canvas, it) }
    }

    private fun drawFitted(canvas: Canvas, bitmap: Bitmap, topFill: Boolean, paint: Paint) {
        val scale = if (topFill) destination.width() / bitmap.width
            else minOf(destination.width() / bitmap.width, destination.height() / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val top = if (topFill) destination.top else destination.centerY() - imageHeight / 2
        sourceRect.set(0, 0, bitmap.width, bitmap.height)
        imageDestination.set(destination.centerX() - imageWidth / 2, top,
            destination.centerX() + imageWidth / 2, top + imageHeight)
        canvas.drawBitmap(bitmap, sourceRect, imageDestination, paint)
    }

    private fun drawFrame(canvas: Canvas, frame: WorkspaceSwapAnimator.Frame) {
        val (bitmap, from, to, empty) = frame
        fun interpolate(a: Float, b: Float) = a + (b - a) * progress
        destination.set(interpolate(from.left, to.left), interpolate(from.top, to.top),
            interpolate(from.right, to.right), interpolate(from.bottom, to.bottom))
        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(destination, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)
        val backdrop = backdrop
        if (empty && backdrop != null) {
            // An empty slot is a faint card over the frosted panel, wherever it travels.
            backdrop.setBounds(0, 0, width, height)
            backdrop.draw(canvas)
            canvas.drawRoundRect(destination, cornerRadius, cornerRadius, emptyCardPaint)
        } else canvas.drawRoundRect(destination, cornerRadius, cornerRadius, cardPaint)
        if (empty) {
            val left = (destination.centerX() - badgeSize / 2f).toInt()
            val top = (destination.centerY() - badgeSize / 2f).toInt()
            badge.setBounds(left, top, left + badgeSize, top + badgeSize)
            badge.draw(canvas)
        } else {
            val fresh = frame.endBitmap?.takeUnless { it.isRecycled }
            // The new page opens below the video as the pane grows. Both pictures keep the
            // same top/width anchor; fitting the new page by height made it shrink then jump.
            if (fresh != null && progress > 0f) drawFitted(canvas, fresh, frame.topFill, bitmapPaint)
            // Fade the old picture over the opaque new one, avoiding a dip through the backdrop.
            if (fresh != null) bitmapPaint.alpha = (255 * (1f - progress)).toInt().coerceIn(0, 255)
            if (bitmap != null && !bitmap.isRecycled) drawFitted(canvas, bitmap, frame.topFill, bitmapPaint)
            bitmapPaint.alpha = 255
        }
        canvas.restoreToCount(save)
        canvas.drawRoundRect(destination, cornerRadius, cornerRadius, borderPaint)
    }
}
