package io.github.lujinxin.nextep.framework

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionRootFrameTest {
    @Test fun aChildUnderTheLiftedRootLandsWhereThePaneShowsIt() {
        val pane = TaskSurfaceCompat.SurfaceFrame(0.7870223f, 0.7870223f, 0f, 631f)
        val root = rootFrame(pane, offsetX = 12, offsetY = 40)
        // Shell lays the child out relative to the root's offset; the root itself sits at that offset.
        val (x, y) = 300f to 900f
        assertEquals(pane.positionX + pane.scaleX * x, root.positionX + root.scaleX * (x - 12), 0.01f)
        assertEquals(pane.positionY + pane.scaleY * y, root.positionY + root.scaleY * (y - 40), 0.01f)
    }
}
