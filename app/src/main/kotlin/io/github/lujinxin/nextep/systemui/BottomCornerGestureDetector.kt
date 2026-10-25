package io.github.lujinxin.nextep.systemui

class BottomCornerGestureDetector(
    private val density: Float,
    private val canActivate: () -> Boolean = { true },
) {
    enum class Result { IGNORED, OBSERVING, ABANDONED, CONFIRMED }

    // A thumb swipe from the corner is short and rarely a clean 45°: accept 24dp on each axis
    // within a 64dp corner, between about 32° and 58° (1.6:1). A 96dp corner with 2.5:1 also
    // took a home swipe leaning 25° off vertical (realme GT8): the workspace closed while the
    // system's home gesture carried on.
    private val startSize = 64f * density
    private val maximumSlope = 1.6f
    private val minimumDistance = 24f * density
    private val directionSlop = 12f * density
    private var observing = false
    private var fromLeft = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downWidth = 0
    private var downHeight = 0
    private var downRotation = 0

    val isObserving: Boolean get() = observing

    /** The last confirmed swipe, described in dp from its corner. */
    var lastConfirmed: String? = null
        private set
    private fun dp(pixels: Float) = (pixels / density).toInt()

    fun reset() { observing = false }

    fun observe(
        action: Int,
        x: Float,
        y: Float,
        pointerCount: Int,
        eventTime: Long,
        screenWidth: Int,
        screenHeight: Int,
        rotation: Int = 0,
    ): Result {
        if (pointerCount != 1 || !x.isFinite() || !y.isFinite() ||
            screenWidth <= 0 || screenHeight <= 0 ||
            x !in 0f..screenWidth.toFloat() || y !in 0f..screenHeight.toFloat()
        ) return finish(Result.ABANDONED)

        if (action == ACTION_DOWN) {
            val cornerWidth = minOf(startSize, screenWidth / 4f)
            val cornerHeight = minOf(startSize, screenHeight / 4f)
            observing = y >= screenHeight - cornerHeight &&
                (x <= cornerWidth || x >= screenWidth - cornerWidth)
            if (!observing) return Result.IGNORED
            if (!canActivate()) return finish(Result.ABANDONED)
            fromLeft = x <= cornerWidth
            downX = x
            downY = y
            downTime = eventTime
            downWidth = screenWidth
            downHeight = screenHeight
            downRotation = rotation
            return Result.OBSERVING
        }
        if (!observing) return Result.IGNORED
        if (screenWidth != downWidth || screenHeight != downHeight || rotation != downRotation ||
            eventTime - downTime !in 0L..1_200L
        ) return finish(Result.ABANDONED)

        if (action == ACTION_MOVE || action == ACTION_UP) {
            val inward = if (fromLeft) x - downX else downX - x
            val upward = downY - y
            if (inward < -directionSlop || upward < -directionSlop) {
                return finish(Result.ABANDONED)
            }
            if (inward >= minimumDistance && upward >= minimumDistance &&
                inward <= upward * maximumSlope && upward <= inward * maximumSlope
            ) {
                // In dp from the corner, for diagnostics: where the swipe began and how it moved.
                lastConfirmed = "corner=${if (fromLeft) "left" else "right"} " +
                    "start=${dp(if (fromLeft) downX else downWidth - downX)},${dp(downHeight - downY)} " +
                    "move=${dp(inward)},${dp(upward)} ms=${eventTime - downTime}"
                return finish(if (canActivate()) Result.CONFIRMED else Result.ABANDONED)
            }
            if (maxOf(inward, upward) >= minimumDistance &&
                minOf(inward, upward) < maxOf(inward, upward) / 4f
            ) return finish(Result.ABANDONED)
        }
        return if (action == ACTION_MOVE) Result.OBSERVING else finish(Result.ABANDONED)
    }

    private fun finish(result: Result): Result {
        if (!observing) return Result.IGNORED
        observing = false
        return result
    }

    private companion object {
        // Android MotionEvent action values keep recognition testable without a device.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }
}
