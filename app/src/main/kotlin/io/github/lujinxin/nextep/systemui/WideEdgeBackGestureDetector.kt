package io.github.lujinxin.nextep.systemui

/**
 * Edge swipe inside the pinned video's screen rectangle. A pinned video is too small for the
 * system edge-back gesture to reach it, so a swipe that starts in the left or right edge band
 * beside the video asks the video itself to go back. Below the video the system gesture is untouched.
 */
class WideEdgeBackGestureDetector(private val density: Float) {
    enum class Result { IGNORED, STARTED, OBSERVING, ABANDONED, CONFIRMED }

    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val bandWidth = 24f * density
    private val confirmDistance = 40f * density
    private val directionSlop = 12f * density
    private val verticalSlop = 48f * density
    private var observing = false
    private var fromLeft = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun reset() { observing = false }

    /** [region] is read only on DOWN: where the pinned video is when the finger lands. */
    fun observe(
        action: Int, x: Float, y: Float, pointerCount: Int, eventTime: Long,
        screenWidth: Int, region: Region?,
    ): Result {
        if (action == ACTION_DOWN) {
            val band = minOf(bandWidth, screenWidth / 8f)
            observing = region != null && screenWidth > 0 && pointerCount == 1 &&
                y >= region.top && y <= region.bottom && x >= region.left && x <= region.right &&
                (x <= band || x >= screenWidth - band)
            if (!observing) return Result.IGNORED
            fromLeft = x <= band
            downX = x
            downY = y
            downTime = eventTime
            return Result.STARTED
        }
        if (!observing) return Result.IGNORED
        if (pointerCount != 1 || !x.isFinite() || !y.isFinite() || eventTime - downTime !in 0L..1_000L) {
            return finish(Result.ABANDONED)
        }
        if (action == ACTION_MOVE || action == ACTION_UP) {
            val inward = if (fromLeft) x - downX else downX - x
            val vertical = kotlin.math.abs(y - downY)
            if (inward < -directionSlop || (vertical > verticalSlop && vertical > inward)) {
                return finish(Result.ABANDONED)
            }
            if (inward >= confirmDistance && vertical <= inward) return finish(Result.CONFIRMED)
        }
        return if (action == ACTION_MOVE) Result.OBSERVING else finish(Result.ABANDONED)
    }

    private fun finish(result: Result): Result {
        observing = false
        return result
    }

    private companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }
}
