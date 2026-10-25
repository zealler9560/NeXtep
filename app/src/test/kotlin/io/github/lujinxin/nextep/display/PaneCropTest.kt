package io.github.lujinxin.nextep.display

import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaneCropTest {
    private fun laidOut(): TaskSwitcherView = TaskSwitcherView(RuntimeEnvironment.getApplication(), 0).apply {
        setOutputSize(1216, 2640, 87)
        // A card with the aspect of the display below the crop.
        measure(View.MeasureSpec.makeMeasureSpec(196, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY))
        layout(0, 0, 196, 412)
    }

    @Test fun theTopBandIsLeftAboveTheCardAndTheRestFillsIt() {
        val output = laidOut().textureView
        // Width-filled; the 87 rows above the card at the card's scale (196/1216).
        assertEquals(196, output.width)
        assertEquals(-14, output.top)
        assertEquals(412f, output.bottom.toFloat(), 2f)
        assertEquals(14, output.clipBounds?.top)
    }
}
