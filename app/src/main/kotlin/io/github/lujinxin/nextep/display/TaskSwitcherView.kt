package io.github.lujinxin.nextep.display

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.WorkbenchAppearance
import io.github.lujinxin.nextep.workspace.SidebarSide

class TaskSwitcherView(
    context: Context,
    private val slotIndex: Int,
) : FrameLayout(context) {
    interface Listener {
        fun onSlotClicked(slotIndex: Int)
        fun onIntentDropped(slotIndex: Int, intent: Intent): Boolean
        fun onSlotDragStarting(drag: SlotTaskDrag): Boolean
        fun onSlotDragStarted(source: TaskSwitcherView, drag: SlotTaskDrag): Boolean
        fun onSlotDragTouch(source: View, event: MotionEvent): Boolean
        fun onSlotBackgroundSwiped(drag: SlotTaskDrag): Boolean
        fun onSlotDragEnded()
        fun onSlotDropped(slotIndex: Int, drag: SlotTaskDrag): Boolean
        fun onContentDropped(slotIndex: Int, event: DragEvent): Boolean = false
        fun onContentDragStarted() {}
        fun onContentDragEntered(slotIndex: Int) {}
        fun onContentDragEnded(accepted: Boolean) {}
    }

    var textureView: SurfaceView = createSurfaceView()
        private set
    private var outputWidth = 0
    private var outputHeight = 0
    private var contentInput: SlotTouchForwarder? = null

    private var appearance = WorkbenchAppearance.DARK
    private val statusView = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    // The last frame of a retained task, shown over the fresh (black) output until the app draws.
    private val coverView = android.widget.ImageView(context).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val clearCover = Runnable { clearCover(fade = true) }
    /** A paused app: its last frame stays over the stopped output until it runs again. */
    private var coverHeld = false
    private val pauseBadge = View(context).apply {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var listener: Listener? = null
    private var busy = false
    private var currentState: SlotState = SlotState.Empty
    private var waiting = false
    private var stateRendered = false
    private var ownsDrag = false
    private var contentDrag = false
    private val backgroundSwipe = SlotBackgroundSwipeGesture(
        view = this,
        currentTask = {
            (currentState as? SlotState.Occupied)?.takeIf { !busy && !waiting && !ownsDrag }
                ?.let { SlotTaskDrag(slotIndex, it.taskId, it.displayId) }
        },
        onStarted = { listener?.onSlotDragStarting(it) == true },
        onDismiss = { listener?.onSlotBackgroundSwiped(it) == true },
        onEnded = { listener?.onSlotDragEnded() },
    )

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(appearance.slotCard)
            setStroke(dp(1), appearance.outline)
        }
        clipToOutline = true
        isClickable = true
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(statusView, LayoutParams(dp(WorkbenchAppearance.BADGE_SIZE_DP),
            dp(WorkbenchAppearance.BADGE_SIZE_DP), Gravity.CENTER))
        addView(coverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(pauseBadge, LayoutParams(dp(WorkbenchAppearance.PAUSE_BADGE_SIZE_DP),
            dp(WorkbenchAppearance.PAUSE_BADGE_SIZE_DP), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        showState(SlotState.Empty)
        setOnDragListener { _, event -> handleDrag(event) }
        setOnClickListener {
            if (contentInput != null) return@setOnClickListener
            // The coordinator serializes exchanges and retains the latest selection.
            // Filtering busy taps here prevents that queue from ever receiving them.
            if (currentState is SlotState.Empty || currentState is SlotState.Ready || currentState is SlotState.Occupied ||
                currentState is SlotState.Failed) {
                listener?.onSlotClicked(slotIndex)
            }
        }
        setOnLongClickListener {
            if (contentInput != null) return@setOnLongClickListener false
            val occupied = currentState as? SlotState.Occupied
                ?: return@setOnLongClickListener false
            if (busy || waiting) return@setOnLongClickListener false
            val drag = SlotTaskDrag(slotIndex, occupied.taskId, occupied.displayId)
            if (listener?.onSlotDragStarting(drag) != true) return@setOnLongClickListener false
            ownsDrag = true
            val started = listener?.onSlotDragStarted(this, drag) == true
            if (!started) finishDrag()
            else {
                textureView.alpha = 0.55f
            }
            started
        }
        setOnTouchListener { view, event ->
            if (contentInput != null) {
                if (!busy && !waiting) contentInput?.touch(event, android.graphics.RectF(
                    textureView.left.toFloat(), textureView.top.toFloat(),
                    textureView.right.toFloat(), textureView.bottom.toFloat()), outputWidth, outputHeight)
                true
            } else if (listener?.onSlotDragTouch(view, event) == true) {
                backgroundSwipe.cancel()
                true
            } else backgroundSwipe.touch(event)
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    internal fun setContentInput(value: SlotTouchForwarder?) {
        if (contentInput === value) return
        contentInput?.cancel()
        backgroundSwipe.cancel()
        contentInput = value
    }

    fun finishInternalDrag() = finishDrag()
    fun setBackgroundSwipeEnabled(enabled: Boolean) = backgroundSwipe.setEnabled(enabled)
    fun setBackgroundSwipeSide(side: SidebarSide) = backgroundSwipe.setSide(side)
    fun cancelBackgroundSwipe() = backgroundSwipe.cancel()
    fun acceptsAppDrop() = !busy && (currentState is SlotState.Ready || currentState is SlotState.Occupied)
    fun setDropHighlighted(highlighted: Boolean) {
        foreground = if (highlighted) dropHighlight() else null
    }

    private fun dropHighlight() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor((appearance.accent and 0x00FFFFFF) or 0x23000000)
        setStroke(dp(2), appearance.accent)
    }

    fun showState(state: SlotState) {
        if (stateRendered && state == currentState && !waiting) return
        stateRendered = true
        if (state != currentState || waiting) backgroundSwipe.cancel()
        // A replaced output surface starts black until its display draws; fade it in too.
        val freshSurface = waiting
        waiting = false
        val previousState = currentState
        currentState = state
        when (state) {
            SlotState.Empty -> showReady()
            is SlotState.Ready -> showReady()
            is SlotState.Occupied -> {
                // Under a cover the fade is invisible, yet every frame of it sent a SurfaceView
                // alpha transaction while the apps were coming back; show the output at once.
                showOccupied(
                    animated = coverView.visibility != VISIBLE && (freshSurface ||
                        previousState !is SlotState.Occupied || previousState.taskId != state.taskId),
                )
            }
            is SlotState.Failed -> showReady()
        }
        if (coverView.visibility == VISIBLE && !coverHeld) {
            removeCallbacks(clearCover)
            when (state) {
                // shortcut: no first-frame signal from a SurfaceView; the rebound app has drawn
                // within this delay on the test phone. Replace with a frame callback if it shows black.
                is SlotState.Occupied -> postDelayed(clearCover, COVER_REVEAL_DELAY_MS)
                // The display is up; the retained task is on its way back, or never comes.
                is SlotState.Ready -> postDelayed(clearCover, COVER_FALLBACK_MS)
                else -> clearCover(fade = false)
            }
        }
    }

    /** Cover the output with [bitmap] until the slot's next occupant has drawn; the caller keeps the bitmap. */
    fun showCover(bitmap: Bitmap) {
        removeCallbacks(clearCover)
        coverView.setImageBitmap(bitmap)
        coverView.animate().cancel()
        coverView.alpha = 1f
        coverView.visibility = VISIBLE
        postDelayed(clearCover, COVER_FALLBACK_MS)
        NeXtepLog.info("slot_cover", "Shown slot=$slotIndex ${bitmap.width}x${bitmap.height}")
    }

    /** Pause: keep [bitmap] (null keeps the output's own last frame) over the stopped app, with the pause badge. */
    fun holdCover(bitmap: Bitmap?) {
        removeCallbacks(clearCover)
        coverHeld = true
        if (bitmap != null) {
            coverView.setImageBitmap(bitmap)
            coverView.animate().cancel()
            coverView.alpha = 1f
            coverView.visibility = VISIBLE
        }
        pauseBadge.background = appearance.pauseBadge(resources.displayMetrics.density)
        pauseBadge.visibility = VISIBLE
        stateDescription = AppLanguage.string(context, R.string.slot_paused)
    }

    /** The app runs again: its next frames take over from the held picture shortly. */
    fun releaseCover() {
        if (!coverHeld) return
        coverHeld = false
        pauseBadge.visibility = GONE
        stateDescription = null
        removeCallbacks(clearCover)
        if (coverView.visibility == VISIBLE) postDelayed(clearCover, COVER_REVEAL_DELAY_MS)
    }

    fun isCoverHeld(): Boolean = coverHeld

    private fun clearCover(fade: Boolean) {
        removeCallbacks(clearCover)
        if (coverView.visibility != VISIBLE || coverHeld) return
        NeXtepLog.info("slot_cover", "Clearing slot=$slotIndex fade=$fade state=${currentState::class.simpleName}")
        coverView.animate().cancel()
        val release = Runnable {
            coverView.visibility = GONE
            coverView.setImageBitmap(null)
        }
        if (fade) coverView.animate().alpha(0f).setDuration(COVER_FADE_MS).withEndAction(release).start()
        else release.run()
    }

    fun showWaiting() {
        backgroundSwipe.cancel()
        waiting = true
        setOutputLayerShown(true)
        textureView.animate().cancel()
        textureView.animate()
            .alpha(0f)
            .scaleX(CONTENT_EXIT_SCALE)
            .scaleY(CONTENT_EXIT_SCALE)
            .setDuration(TRANSITION_OUT_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .start()
        statusView.animate().cancel()
        setStatusVisible(currentState !is SlotState.Occupied)
    }

    fun setBusy(isBusy: Boolean) {
        busy = isBusy
        if (isBusy) contentInput?.cancel()
        if (isBusy) backgroundSwipe.cancel()
        if (!isBusy) {
            showState(currentState)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    /** [crop]: rows at the top of the output the card leaves out: the status band every app keeps blank
     * there, its display providing the same inset as the phone's; the main window crops it too. */
    fun setOutputSize(width: Int, height: Int, crop: Int = 0) {
        require(width > 0 && height > 0)
        if (outputWidth == width && outputHeight == height && topCrop == crop) return
        contentInput?.cancel()
        outputWidth = width
        outputHeight = height
        topCrop = crop.coerceIn(0, height - 1)
        requestLayout()
    }

    private var topCrop = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (outputWidth <= 0 || outputHeight <= 0) return
        val scale = minOf(measuredWidth.toFloat() / outputWidth, measuredHeight.toFloat() / (outputHeight - topCrop))
        val widthSpec = MeasureSpec.makeMeasureSpec((outputWidth * scale).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec((outputHeight * scale).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY)
        textureView.measure(widthSpec, heightSpec)
        coverView.measure(widthSpec, heightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (outputWidth <= 0 || outputHeight <= 0) return
        // The output sits so that its rows below the crop are centred in the card; the rows above are
        // clipped by the card (and by the SurfaceView's own clip, which also crops its surface).
        val w = textureView.measuredWidth
        val h = textureView.measuredHeight
        val cropped = topCrop * h / outputHeight
        val x = (width - w) / 2
        val y = (height - (h - cropped)) / 2 - cropped
        textureView.layout(x, y, x + w, y + h)
        coverView.layout(x, y, x + w, y + h)
        textureView.clipBounds = Rect(0, cropped, w, h)
    }

    /** The app's picture at this card's size, for a cover that outlives the capture. */
    internal fun captureCardPreview(): Bitmap? = captureProtectedPreview(visibleOnly = false)?.let { full ->
        val width = textureView.width
        val height = textureView.height
        // An unmeasured view: keep the capture as is, the cover scales it to fit.
        if (width <= 0 || height <= 0) full
        else Bitmap.createScaledBitmap(full, width, height, true).also { if (it !== full) full.recycle() }
    }

    // Internal transitions dispose the frame immediately.
    // ScreenCapture still excludes any secure layers belonging to the source app.
    /** [visibleOnly]: the part the card shows (below its crop); else the whole output, laid out like it. */
    internal fun captureProtectedPreview(visibleOnly: Boolean = true): Bitmap? = runCatching {
        fun shown(full: Bitmap): Bitmap {
            val rows = if (visibleOnly && outputHeight > 0) topCrop * full.height / outputHeight else 0
            if (rows <= 0 || rows >= full.height) return full
            return Bitmap.createBitmap(full, 0, rows, full.width, full.height - rows).also { if (it !== full) full.recycle() }
        }
        // A paused app is stopped and may draw nothing; its held picture is what the card shows.
        if (coverHeld) (coverView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?.let { return@runCatching shown(it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)) }
        val task = currentState as? SlotState.Occupied ?: return@runCatching null
        // The task fills this slot's canvas; that is the capture's crop.
        val crop = if (outputWidth > 0 && outputHeight > 0) Rect(0, 0, outputWidth, outputHeight) else null
        val bitmap = io.github.lujinxin.nextep.framework.TaskSurfaceCompat.capturePreview(task.taskId, crop)
            ?.let(::shown) ?: return@runCatching null
        // TextureView returns its logical portrait buffer. Follow the rail's
        // actual transform so reverse-landscape drag/swap previews face correctly.
        val panelRotation = (parent as? View)?.rotation ?: 0f
        if (panelRotation != 0f) {
            try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    android.graphics.Matrix().apply { postRotate(panelRotation) }, true)
            } finally { bitmap.recycle() }
        } else bitmap
    }.getOrNull()

    /** getGlobalVisibleRect is relative to the ViewRoot, not display 0. The
     * sidebar is a separate window whose physical offset must be included. */
    fun getScreenVisibleRect(bounds: Rect): Boolean {
        if (!getGlobalVisibleRect(bounds)) return false
        val windowOrigin = IntArray(2)
        rootView.getLocationOnScreen(windowOrigin)
        bounds.offset(windowOrigin[0], windowOrigin[1])
        return true
    }

    override fun onDetachedFromWindow() {
        contentInput?.cancel()
        backgroundSwipe.cancel()
        finishDrag()
        super.onDetachedFromWindow()
    }

    private fun finishDrag() {
        if (!ownsDrag) return
        ownsDrag = false
        listener?.onSlotDragEnded()
        if (!busy && currentState is SlotState.Occupied) textureView.alpha = 1f
    }

    private fun handleDrag(event: DragEvent): Boolean {
        return when (event.action) {
        DragEvent.ACTION_DRAG_STARTED -> {
            val localDrag = event.localState as? SlotTaskDrag
            val accepted = !busy && (localDrag != null &&
                currentState.inDragTargetStates() || currentState !is SlotState.Occupied &&
                AppDragContract.accepts(event.clipDescription) ||
                currentState is SlotState.Occupied && ContentDropCompat.accepts(event.clipDescription))
            contentDrag = accepted && localDrag == null && ContentDropCompat.accepts(event.clipDescription)
            if (contentDrag) listener?.onContentDragStarted()
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex started accepted=$accepted busy=$busy state=$currentState " +
                    "content=$contentDrag",
            )
            accepted
        }
        DragEvent.ACTION_DRAG_ENTERED -> {
            if (contentDrag && !busy) listener?.onContentDragEntered(slotIndex)
            if (!busy) {
                if (currentState is SlotState.Occupied) foreground = dropHighlight()
                else showReady(highlighted = true)
            }
            true
        }
        DragEvent.ACTION_DRAG_EXITED -> {
            showState(currentState)
            foreground = null
            true
        }
        DragEvent.ACTION_DROP -> {
            val localDrag = event.localState as? SlotTaskDrag
            if (localDrag != null && width > 0 && height > 0) {
                val bounds = Rect()
                getScreenVisibleRect(bounds)
                localDrag.dropPoint = if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) {
                    android.graphics.PointF(bounds.right - event.y * bounds.width() / height,
                        bounds.top + event.x * bounds.height() / width)
                } else android.graphics.PointF(bounds.left + event.x, bounds.top + event.y)
            }
            val intent = AppDragContract.readLaunchIntent(event.clipData)
            val handled = !busy && if (localDrag != null) {
                listener?.onSlotDropped(slotIndex, localDrag) == true
            } else if (intent != null) listener?.onIntentDropped(slotIndex, intent) == true
            else listener?.onContentDropped(slotIndex, event) == true
            NeXtepLog.info(
                "slot_drag",
                "slot=$slotIndex drop handled=$handled component=${intent?.component}",
            )
            handled
        }
        DragEvent.ACTION_DRAG_ENDED -> {
            if (contentDrag) listener?.onContentDragEnded(event.result)
            contentDrag = false
            foreground = null
            if (!busy) showState(currentState)
            if ((event.localState as? SlotTaskDrag)?.slotIndex == slotIndex) finishDrag()
            true
        }
        else -> true
        }
    }

    private fun SlotState.inDragTargetStates(): Boolean = this is SlotState.Ready || this is SlotState.Occupied

    private fun showReady(highlighted: Boolean = false) {
        // No fade-out of the departed app: on ColorOS 16 each step of it showed the layer again, and the
        // app's last frame lingered in the emptied pane. Moves that empty a pane animate a card over it.
        textureView.animate().cancel()
        textureView.alpha = 0f
        textureView.scaleX = 1f
        textureView.scaleY = 1f
        hideOutputIfEmpty()
        badgeHighlighted = highlighted
        statusView.background = appearance.emptyBadge(resources.displayMetrics.density, highlighted)
        statusView.animate().cancel()
        // Empty slots remain actionable when a buffer or animation is interrupted.
        setStatusVisible(true)
    }

    private fun showOccupied(animated: Boolean) {
        statusView.animate().cancel()
        setStatusVisible(false)
        setOutputLayerShown(true)
        textureView.animate().cancel()
        // A coordinated exchange already animates a protected preview. The live
        // destination must match its final size when that preview is revealed.
        if (animated && !busy) {
            textureView.alpha = 0f
            textureView.scaleX = CONTENT_ENTER_SCALE
            textureView.scaleY = CONTENT_ENTER_SCALE
            textureView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRANSITION_IN_DURATION_MS)
                .setInterpolator(transitionInterpolator)
                .start()
        } else {
            textureView.alpha = 1f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
    }

    /**
     * ColorOS 16 keeps compositing an emptied pane's last frame whatever the view's alpha, and it
     * shows faintly through the translucent empty card; the layer itself is hidden while empty.
     */
    fun hideOutputIfEmpty() {
        if (currentState is SlotState.Ready || currentState is SlotState.Empty) setOutputLayerShown(false)
    }

    private fun setOutputLayerShown(shown: Boolean) {
        val layer = textureView.surfaceControl?.takeIf { it.isValid } ?: return
        runCatching { android.view.SurfaceControl.Transaction().setVisibility(layer, shown).apply() }
            .onFailure { NeXtepLog.warn("slot_output", "Output layer visibility unavailable slot=$slotIndex", it) }
    }

    private fun setStatusVisible(visible: Boolean) {
        statusView.animate().cancel()
        statusView.visibility = if (visible) VISIBLE else GONE
        statusView.alpha = 1f
        statusView.scaleX = 1f
        statusView.scaleY = 1f
    }

    private var badgeHighlighted = false

    internal fun setAppearance(value: WorkbenchAppearance) {
        appearance = value
        (background as? GradientDrawable)?.apply { setColor(value.slotCard); setStroke(dp(1), value.outline) }
        statusView.background = value.emptyBadge(resources.displayMetrics.density, badgeHighlighted)
        if (coverHeld) pauseBadge.background = value.pauseBadge(resources.displayMetrics.density)
    }

    fun isShowingEmptyBadge(): Boolean = statusView.visibility == VISIBLE

    // Source apps' secure windows remain subject to Android's virtual-display restrictions.
    private fun createSurfaceView(): SurfaceView = SurfaceView(context).apply { setZOrderMediaOverlay(true) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val transitionInterpolator = DecelerateInterpolator(1.6f)

    private companion object {
        const val TRANSITION_OUT_DURATION_MS = 120L
        const val TRANSITION_IN_DURATION_MS = 220L
        const val COVER_REVEAL_DELAY_MS = 600L
        const val COVER_FALLBACK_MS = 2_500L
        const val COVER_FADE_MS = 200L
        const val CONTENT_ENTER_SCALE = 0.96f
        const val CONTENT_EXIT_SCALE = 0.98f
    }

}
