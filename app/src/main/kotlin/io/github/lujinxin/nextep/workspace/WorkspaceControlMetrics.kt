package io.github.lujinxin.nextep.workspace

/** Shared dimensions for control content and the workspace region it occupies. */
internal object WorkspaceControlMetrics {
    const val STATUS_HEIGHT_DP = 40
    const val ACTION_HEIGHT_DP = 54
    const val APP_ICON_SIZE_DP = 50
    const val APP_ROW_GAP_DP = 10
    const val APP_BOTTOM_PADDING_DP = 0
    const val CONTENT_BOTTOM_PADDING_DP = 2
    const val LARGE_SCREEN_MIN_SHORT_SIDE_DP = 600
    /** The rail's horizontal space beside its slots: half towards the screen edge, half towards the
     * main window, each as wide as the gap between slots. */
    const val RAIL_GAP_DP = 8
    const val SLOT_GAP_DP = 4

    /** The compact strip that version 9 records were solved with (its sizes before 2.5): migration only. */
    const val COMPACT_HEIGHT_DP = 144

}
