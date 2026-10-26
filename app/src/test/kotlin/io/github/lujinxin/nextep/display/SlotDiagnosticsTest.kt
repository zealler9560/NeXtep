package io.github.lujinxin.nextep.display

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlotDiagnosticsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun aPaneWithoutDisplayOrTaskIsDescribedWithoutThrowing() {
        val line = SlotDiagnostics.describe(context, 2, null, null, android.view.SurfaceView(context), "active=false")
        assertTrue(line, line.startsWith("slot=2 active=false display=none output=att=false"))
    }

    @Test fun aMissingTaskReadsAsGoneWithNoLayer() {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        val line = SlotDiagnostics.describe(context, 0, display, 4242, android.view.SurfaceView(context), "")
        assertTrue(line, line.contains("display=0 state=") && line.contains("expected=4242 at=gone layer=none"))
    }
}
