package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceControlMetricsTest {
    @Test
    fun fullAndButtonLayoutsKeepEnoughHeightForTheWholeIconRow() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            // A small 360 dp phone with a 40 dp status bar, of which the main app keeps its 16 dp gap.
            val status = 40 * 3
            val crop = status - WorkspaceControlMetrics.STATUS_KEEP_DP * 3
            val g = WorkspaceLayoutProfile.create(1080, 2354, 480, insets, statusBar = crop).geometry
            assertTrue(g.topHeight < g.availableHeight / 4)
            // Below the status bar the strip holds the whole block; only the gap above the windows may shrink.
            val below = g.controlStatusBand + g.topHeight - status
            assertTrue(below >= (WorkspaceControlMetrics.ACTION_HEIGHT_DP + WorkspaceControlMetrics.APP_ROW_GAP_DP +
                WorkspaceControlMetrics.APP_ICON_SIZE_DP + WorkspaceControlMetrics.APP_BOTTOM_PADDING_DP) * 3)
        }
    }

    @Test
    fun oppositeLandscapeControlsHaveTheSameCompactThickness() {
        for (rotation in listOf(1, 3)) {
            val g = WorkspaceLayoutProfile.create(2354, 1080, 480, WorkspaceNavigationInsets(), rotation).geometry
            assertEquals(505, g.topHeight)
        }
    }
}
