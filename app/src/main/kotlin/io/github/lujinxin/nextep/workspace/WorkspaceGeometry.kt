package io.github.lujinxin.nextep.workspace

import kotlin.math.roundToInt

data class WorkspaceNavigationInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class WorkspaceGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val topHeight: Int,
    val sidebarWidth: Int,
    val sidebarSide: SidebarSide,
    val navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
    val displayRotation: Int = 0,
    /** Control strip at the phone's physical bottom instead of its top; the rail and main region shift to fill. */
    val controlAtBottom: Boolean = false,
    /**
     * Portrait: the status bar band, outside every pane. Apps keep it blank at their top, so the main
     * app is cropped by it and the panes share the aspect of the screen below the status bar.
     */
    val statusInset: Int = 0,
    /**
     * Under a wide picture the main app is laid out at the available width and this height, so it
     * shares the rail cards' aspect and fills the main window edge to edge; 0 keeps the native canvas.
     */
    val mainCanvasHeight: Int = 0,
    /** Always 0 now: under gesture navigation the main window and the panes reach the screen bottom, with the
     * handle over the app's own bottom bar as everywhere else. Kept for the published layout's field. */
    val bottomGap: Int = 0,
) {
    val isLandscape: Boolean get() = screenWidth > screenHeight
    // Surface rotations are quarter turns from the phone's natural portrait axes.
    // The phone top lands on the display's left for ROTATION_90; text keeps that
    // orientation while the control strip may sit at either physical end.
    private val phoneTopOnLeft: Boolean get() = isLandscape && displayRotation == 1
    val isControlOnLeft: Boolean get() = isLandscape && (phoneTopOnLeft != controlAtBottom)
    val panelRotationDegrees: Float get() = if (!isLandscape) 0f else if (phoneTopOnLeft) -90f else 90f
    // Project the selected button's unchanged diagram into display coordinates.
    // Rotation never changes the selected layout or the diagram in panel axes.
    val panelSidebarSide: SidebarSide get() = SidebarButtonLayout.sideInPanel(sidebarSide)
    val isSidebarAtTop: Boolean get() =
        SidebarButtonLayout.isAtScreenTop(sidebarSide, isLandscape, phoneTopOnLeft)

    // The task still occupies the full display. Its navigation-safe source rectangle
    // is fitted uniformly, so the native navigation padding is not scaled a second time.
    val availableLeft: Int get() = navigationInsets.left
    val availableTop: Int get() = navigationInsets.top + statusInset
    val availableRight: Int get() = screenWidth - navigationInsets.right
    val availableBottom: Int get() = screenHeight - navigationInsets.bottom
    val availableWidth: Int get() = availableRight - availableLeft
    val availableHeight: Int get() = availableBottom - availableTop

    init {
        require(screenWidth > 1 && screenHeight > 1)
        require(displayRotation in 0..3)
        require(navigationInsets.left >= 0 && navigationInsets.top >= 0 &&
            navigationInsets.right >= 0 && navigationInsets.bottom >= 0)
        require(navigationInsets.left <= screenWidth - 2 &&
            navigationInsets.right <= screenWidth - navigationInsets.left - 2)
        require(navigationInsets.top <= screenHeight - 2 &&
            navigationInsets.bottom <= screenHeight - navigationInsets.top - 2)
        require(availableWidth > 1 && availableHeight > 1)
        require(topHeight in 1 until (if (isLandscape) availableWidth else availableHeight))
        require(bottomGap >= 0 && contentHeight > 1)
        require(statusInset >= 0 && statusInset <= screenHeight - navigationInsets.top - navigationInsets.bottom - 2)
        require(sidebarWidth in 1 until (if (isLandscape) availableHeight else availableWidth))
    }

    // Control-strip thickness and slot-rail thickness use portrait logical axes.
    val controlWidth: Int get() = if (isLandscape) topHeight else availableWidth
    val controlHeight: Int get() = if (isLandscape) availableHeight else topHeight
    val controlLeft: Int get() = if (isLandscape && !isControlOnLeft) availableRight - topHeight else availableLeft
    val controlTop: Int get() = if (!isLandscape && controlAtBottom) availableBottom - topHeight else availableTop
    val sidebarLeft: Int get() = if (isLandscape) contentLeft else if (sidebarSide == SidebarSide.LEFT) availableLeft else contentRight
    val sidebarTop: Int get() = if (!isLandscape) contentTop else
        if (isSidebarAtTop) availableTop else availableBottom - sidebarWidth
    val sidebarPhysicalWidth: Int get() = if (isLandscape) contentWidth else sidebarWidth
    val sidebarPhysicalHeight: Int get() = if (isLandscape) sidebarWidth else contentHeight
    val sidebarLogicalHeight: Int get() = if (isLandscape) contentWidth else contentHeight

    val contentTop: Int get() = availableTop + if (!isLandscape) (if (controlAtBottom) 0 else topHeight) else
        if (isSidebarAtTop) sidebarWidth else 0
    val contentBottom: Int get() = contentTop + contentHeight

    val contentWidth: Int
        get() = availableWidth - if (isLandscape) topHeight else sidebarWidth

    val contentHeight: Int
        get() = availableHeight - if (isLandscape) sidebarWidth else topHeight + bottomGap

    val contentLeft: Int
        get() = availableLeft + if (isLandscape) {
            if (isControlOnLeft) topHeight else 0
        } else if (sidebarSide == SidebarSide.LEFT) sidebarWidth else 0

    val contentRight: Int
        get() = contentLeft + contentWidth

    val contentScale: Float
        get() = minOf(contentWidth.toFloat() / availableWidth, contentHeight.toFloat() / availableHeight)

    val contentTranslationX: Float
        get() = contentLeft + (contentWidth - availableWidth * contentScale) / 2f - availableLeft * contentScale

    val contentTranslationY: Float
        // The control and rail proportions preserve the source aspect ratio.
        get() = contentTop - availableTop * contentScale

    // The main app's own transform: width-fit on a shortened canvas, otherwise the common fit.
    // Windows that cannot re-lay out (Launcher) keep contentScale.
    val mainScale: Float get() = if (mainCanvasHeight > 0) contentWidth.toFloat() / availableWidth else contentScale
    val mainTranslationX: Float get() = if (mainCanvasHeight > 0) contentLeft - availableLeft * mainScale else contentTranslationX
    val mainTranslationY: Float get() = if (mainCanvasHeight > 0) contentTop - availableTop * mainScale else contentTranslationY
    /** Task bounds height that lays the main app out at [mainCanvasHeight] below the usual insets; 0 = native. */
    val mainTaskHeight: Int get() = if (mainCanvasHeight > 0) mainCanvasHeight + availableTop + (screenHeight - availableBottom) else 0
    /** The shortened canvas sits at the screen bottom, where WM lays out the input method, so the app's
     * keyboard inset is the keyboard's full height. */
    val mainTaskTop: Int get() = if (mainCanvasHeight > 0) (screenHeight - mainTaskHeight).coerceAtLeast(0) else 0
    /** The input method container's offset: the main app's transform, applied in screen coordinates. */
    val imeTranslationY: Float get() = mainTranslationY - mainTaskTop * mainScale

    /** The status band above the control strip in portrait-top; the control window covers it too. */
    val controlStatusBand: Int get() = if (!isLandscape && !controlAtBottom) statusInset else 0

    /**
     * The same main window for an app that hides the status bar: its top is content, so nothing is
     * cropped and the whole app fits the window's height. Below a bottom strip it keeps the crop.
     */
    fun uncropped(): WorkspaceGeometry = if (controlStatusBand == 0 || mainCanvasHeight > 0) this
        else copy(statusInset = 0, topHeight = topHeight + statusInset)

    companion object {
        const val TOP_HEIGHT_FRACTION = 0.265f
        const val SIDEBAR_WIDTH_FRACTION = 0.267f

        fun forDisplay(
            screenWidth: Int,
            screenHeight: Int,
            sidebarSide: SidebarSide = SidebarSide.RIGHT,
            density: Float,
            navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
            displayRotation: Int = 0,
            controlAtBottom: Boolean = false,
            statusInset: Int = 0,
        ): WorkspaceGeometry {
            require(screenWidth > 1 && screenHeight > 1) { "Display dimensions must be usable" }
            require(density.isFinite() && density > 0f) { "Display density must be positive and finite" }
            val left = navigationInsets.left.coerceIn(0, screenWidth - 2)
            val top = navigationInsets.top.coerceIn(0, screenHeight - 2)
            val safeInsets = WorkspaceNavigationInsets(
                left, top,
                navigationInsets.right.coerceIn(0, screenWidth - left - 2),
                navigationInsets.bottom.coerceIn(0, screenHeight - top - 2),
            )
            // Portrait: the status band is solved like a top inset. Panes take the aspect of the screen
            // below it, and the main app is cropped by it instead of showing its blank top. [statusInset]
            // leaves the main app a gap under a top strip; with the strip at the bottom nothing covers the
            // status bar, so the panes start below all of it.
            val keep = if (controlAtBottom && statusInset > 0) (WorkspaceControlMetrics.STATUS_KEEP_DP * density).roundToInt() else 0
            val safeStatusInset = if (screenHeight > screenWidth)
                (statusInset + keep).coerceIn(0, screenHeight - top - safeInsets.bottom - 2) else 0
            val availableWidth = screenWidth - safeInsets.left - safeInsets.right
            val availableHeight = screenHeight - safeInsets.top - safeInsets.bottom - safeStatusInset
            val landscape = screenWidth > screenHeight
            val longSide = if (landscape) availableWidth else availableHeight
            val shortSide = if (landscape) availableHeight else availableWidth
            val gap = (WorkspaceControlMetrics.RAIL_GAP_DP * density).toInt().coerceAtLeast(1)
            val slotGap = (WorkspaceControlMetrics.SLOT_GAP_DP * density).toInt().coerceAtLeast(1)
            val count = io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
            // Solve main and all preview rectangles together, including gaps.
            // Their canvases share the recorded phone aspect ratio.
            val solved = ((longSide + count * gap * longSide.toDouble() / shortSide -
                (count - 1) * slotGap) / (count + 1)).roundToInt()
                .coerceIn(1, (longSide / 2).coerceAtLeast(1))
            // A short phone may solve a strip too low for its controls; it keeps them whole, and the
            // rail narrows with the main window so the cards sit a little apart instead.
            val strip = if (landscape || safeStatusInset == 0) 0 else (density * (WorkspaceControlMetrics.HEADER_MIN_DP +
                if (controlAtBottom) WorkspaceControlMetrics.STATUS_HEIGHT_DP else WorkspaceControlMetrics.STATUS_KEEP_DP)).toInt()
            val controlThickness = maxOf(solved, strip).coerceAtMost((longSide / 2).coerceAtLeast(1))
            val railWidth = proportionalRail(shortSide, longSide, controlThickness)
            return WorkspaceGeometry(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                topHeight = controlThickness,
                sidebarWidth = railWidth,
                sidebarSide = sidebarSide,
                navigationInsets = safeInsets,
                displayRotation = displayRotation,
                controlAtBottom = controlAtBottom,
                statusInset = safeStatusInset,
            )
        }

        // Round down so portrait fitting is height-limited: no top/bottom
        // letterbox, no crop or stretch, and less than one pixel of side slack.
        internal fun proportionalRail(shortSide: Int, longSide: Int, top: Int): Int =
            (shortSide.toLong() * top / longSide).toInt().coerceIn(1, shortSide - 1)
    }
}
