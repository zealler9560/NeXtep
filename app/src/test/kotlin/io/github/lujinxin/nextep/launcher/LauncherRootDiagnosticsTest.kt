package io.github.lujinxin.nextep.launcher

import android.view.WindowManager
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherRootDiagnosticsTest {
    @Test fun geometryDoesNotExportWindowTitleOrContent() {
        val view = TextView(RuntimeEnvironment.getApplication()).apply {
            text = "private card contents"
            layoutParams = WindowManager.LayoutParams().apply {
                title = "private window title"
                x = -1080
                y = 42
            }
            layout(0, 0, 1080, 2400)
            scaleX = 0.8f
            scaleY = 0.8f
        }
        val snapshot = LauncherTransformController.rootGeometry(view)
        assertTrue(snapshot.contains("size=1080x2400"))
        assertTrue(snapshot.contains("windowXY=-1080,42"))
        assertTrue(snapshot.contains("scale=0.8,0.8"))
        assertFalse(snapshot.contains("private"))
    }
}
