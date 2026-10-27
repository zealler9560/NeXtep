package io.github.lujinxin.nextep.systemui

import io.github.lujinxin.nextep.systemui.WideEdgeBackGestureDetector.Region
import io.github.lujinxin.nextep.systemui.WideEdgeBackGestureDetector.Result.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WideEdgeBackGestureDetectorTest {
    private val video = Region(0, 100, 400, 325)
    private fun detector(density: Float = 1f) = WideEdgeBackGestureDetector(density)
    private fun WideEdgeBackGestureDetector.at(
        action: Int, x: Float, y: Float, time: Long = 100L, pointers: Int = 1, region: Region? = video,
    ) = observe(action, x, y, pointers, time, 400, region)

    @Test fun inwardSwipeFromEitherEdgeInsideTheVideoConfirms() {
        for ((startX, dir) in listOf(10f to 1f, 390f to -1f)) {
            val d = detector()
            assertEquals(STARTED, d.at(0, startX, 200f))
            assertEquals(OBSERVING, d.at(2, startX + dir * 20f, 202f, 150L))
            assertEquals(CONFIRMED, d.at(2, startX + dir * 40f, 205f, 200L))
        }
    }

    @Test fun downBelowOrAboveTheVideoOrAwayFromTheEdgeIsLeftToTheSystem() {
        assertEquals(IGNORED, detector().at(0, 10f, 400f))
        assertEquals(IGNORED, detector().at(0, 10f, 50f))
        assertEquals(IGNORED, detector().at(0, 100f, 200f))
        assertEquals(IGNORED, detector().at(0, 10f, 200f, region = null))
    }

    @Test fun mostlyVerticalOutwardSlowOrMultiTouchSwipesAreAbandoned() {
        val vertical = detector().apply { at(0, 10f, 200f) }
        assertEquals(ABANDONED, vertical.at(2, 20f, 270f, 150L))
        val outward = detector().apply { at(0, 20f, 200f) }
        assertEquals(ABANDONED, outward.at(2, 2f, 200f, 150L))
        val slow = detector().apply { at(0, 10f, 200f) }
        assertEquals(ABANDONED, slow.at(2, 60f, 200f, 1_500L))
        val twoFingers = detector().apply { at(0, 10f, 200f) }
        assertEquals(ABANDONED, twoFingers.at(2, 60f, 200f, 150L, pointers = 2))
    }

    @Test fun shortReleaseDoesNotConfirmAndDensityScalesTheDistance() {
        val tap = detector().apply { at(0, 10f, 200f) }
        assertEquals(ABANDONED, tap.at(1, 30f, 200f, 150L))
        val dense = detector(2f)
        dense.at(0, 20f, 200f)
        assertEquals(OBSERVING, dense.at(2, 80f, 200f, 150L))
        assertEquals(CONFIRMED, dense.at(2, 100f, 200f, 160L))
    }
}
