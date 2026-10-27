package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs

/**
 * The sidebar rail's panel: owns the slot column's scroll offset.
 *
 * The cards keep the layout [NeXtepWindowController.applySlotLayout] gives them; this panel
 * only tracks how far the column is scrolled and reports each change, and the controller
 * translates the cards. A vertical-dominant drag starting on a card scrolls the column —
 * the card receives ACTION_CANCEL, which its gestures already handle — while taps,
 * horizontal swipes and long-press drags are untouched: a card that begins an internal
 * drag disallows interception for the rest of that gesture, as Android intends, so the
 * drag keeps vertical moves for itself.
 *
 * Touch arrives in panel-local axes: WorkspacePanelHost rotates this panel's subtree, so
 * event Y always runs along the rail, in portrait and landscape alike.
 */
internal class SidebarPanelView(context: Context) : FrameLayout(context) {
    /** The furthest the column may scroll; the controller sets it on every layout. */
    var maxScrollOffsetPx: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            setScrollOffset(scrollOffsetPx) // re-clamp against the new range
        }

    var scrollOffsetPx: Int = 0
        private set

    /** Called on the UI thread whenever [scrollOffsetPx] changes. */
    var onScrollOffsetChanged: ((Int) -> Unit)? = null

    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val scroller = OverScroller(context)

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var velocityTracker: VelocityTracker? = null

    fun setScrollOffset(offset: Int) {
        val clamped = offset.coerceIn(0, maxScrollOffsetPx)
        if (clamped == scrollOffsetPx) return
        scrollOffsetPx = clamped
        onScrollOffsetChanged?.invoke(clamped)
    }

    /** Back to the top, stopping any fling: the workspace shows the column from its start. */
    fun resetScroll() {
        scroller.abortAnimation()
        setScrollOffset(0)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                activePointerId = MotionEvent.INVALID_POINTER_ID
                // A fresh touch settles a running fling where it is; a move that follows
                // may still claim the gesture below.
                if (!scroller.isFinished) scroller.abortAnimation()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                // Vertical-dominant only: horizontal moves belong to the cards (background
                // swipe), and a move under the slop is still a tap or a long-press.
                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.25f) {
                    activePointerId = event.getPointerId(0)
                    lastY = event.y
                    obtainVelocityTracker(event)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                activePointerId = MotionEvent.INVALID_POINTER_ID
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastY = event.y
                if (!scroller.isFinished) scroller.abortAnimation()
                obtainVelocityTracker(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val y = event.getY(index)
                // The finger moving up (y decreasing) scrolls the column up: offset grows.
                setScrollOffset(scrollOffsetPx + (lastY - y).toInt())
                lastY = y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Hand the gesture to the remaining finger without a jump.
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val other = if (event.actionIndex == 0) 1 else 0
                    if (other < event.pointerCount) {
                        activePointerId = event.getPointerId(other)
                        lastY = event.getY(other)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.let { tracker ->
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val fingerVelocity = tracker.yVelocity
                    if (abs(fingerVelocity) > configuration.scaledMinimumFlingVelocity) {
                        // The offset runs against the finger: an upward flick keeps it growing.
                        scroller.fling(0, scrollOffsetPx, 0, -fingerVelocity.toInt(),
                            0, 0, 0, maxScrollOffsetPx, 0, touchSlop)
                        postInvalidateOnAnimation()
                    }
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setScrollOffset(scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    private fun obtainVelocityTracker(event: MotionEvent) {
        recycleVelocityTracker()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}
