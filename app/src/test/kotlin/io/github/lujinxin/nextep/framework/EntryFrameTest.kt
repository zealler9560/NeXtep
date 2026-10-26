package io.github.lujinxin.nextep.framework

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntryFrameTest {
    private val crop = Rect(0, 120, 1080, 2300)

    /** The task's visible slice, scaled and positioned, covers exactly the icon's rectangle. */
    private fun mapped(frame: TaskSurfaceCompat.SurfaceFrame): Rect {
        val slice = checkNotNull(frame.crop)
        return Rect(
            Math.round(frame.positionX + slice.left * frame.scaleX),
            Math.round(frame.positionY + slice.top * frame.scaleY),
            Math.round(frame.positionX + slice.right * frame.scaleX),
            Math.round(frame.positionY + slice.bottom * frame.scaleY),
        )
    }

    @Test fun aSquareIconMapsToItselfFromACentredSlice() {
        val icon = Rect(100, 1800, 220, 1920)
        val frame = entryFrame(icon, crop)
        assertEquals(icon, mapped(frame))
        assertEquals(crop.left, frame.crop!!.left)
        assertEquals(crop.right, frame.crop!!.right)
        assertEquals(crop.centerY(), frame.crop!!.centerY())
        assertTrue(crop.contains(frame.crop!!))
    }

    @Test fun aWideIconKeepsItsAspectAndATallOneIsLimitedToTheCrop() {
        val wide = Rect(600, 300, 900, 400)
        assertEquals(wide, mapped(entryFrame(wide, crop)))
        // Taller than the screen's aspect: the slice is the whole crop, as tall as it gets.
        val tall = Rect(0, 0, 10, 1000)
        assertEquals(crop, entryFrame(tall, crop).crop)
    }
}
