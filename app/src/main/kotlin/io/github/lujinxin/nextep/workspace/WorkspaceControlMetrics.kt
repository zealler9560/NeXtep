package io.github.lujinxin.nextep.workspace

/** Shared dimensions for control content and the workspace region it occupies. */
internal object WorkspaceControlMetrics {
    const val STATUS_HEIGHT_DP = 40
    /** Of the app's own status band, this much stays visible above the main app as its top gap. */
    const val STATUS_KEEP_DP = 16
    /** Top strip: the title and app rows sit at its bottom, the app row this far above the windows. */
    const val CONTENT_GAP_DP = 24
    /** Gesture navigation, portrait under a top strip: the panes end this far above the screen bottom. */
    const val ACTION_HEIGHT_DP = 54
    const val APP_ICON_SIZE_DP = 50
    const val APP_ROW_GAP_DP = 10
    const val APP_BOTTOM_PADDING_DP = 0
    /** The least strip a top or bottom control needs beside its status reserve: its rows plus a 4dp gap. */
    const val HEADER_MIN_DP = ACTION_HEIGHT_DP + APP_ROW_GAP_DP + APP_ICON_SIZE_DP + APP_BOTTOM_PADDING_DP + 4
    const val LARGE_SCREEN_MIN_SHORT_SIDE_DP = 600
    /** The rail's horizontal space beside its slots: half towards the screen edge, half towards the
     * main window, each as wide as the gap between slots. */
    const val RAIL_GAP_DP = 8
    const val SLOT_GAP_DP = 4
}
