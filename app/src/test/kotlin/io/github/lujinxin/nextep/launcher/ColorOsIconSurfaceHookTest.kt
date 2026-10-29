package io.github.lujinxin.nextep.launcher

import android.graphics.Matrix
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColorOsIconSurfaceHookTest {
    @Test fun iconSurfaceUsesTheSameFitAsTheDecorWithoutMutatingNativeCoordinates() {
        val native = Matrix().apply { setScale(2f, 3f); postTranslate(100f, 200f) }
        val fit = TouchCoordinateMapper.Transform(0.75f, 0.75f, 20f, 500f)
        val mapped = floatArrayOf(10f, 20f)
        ColorOsIconSurfaceHook.fittedMatrix(native, fit).mapPoints(mapped)
        assertArrayEquals(floatArrayOf(110f, 695f), mapped, 0.001f)
        val unchanged = floatArrayOf(10f, 20f)
        native.mapPoints(unchanged)
        assertArrayEquals(floatArrayOf(120f, 260f), unchanged, 0.001f)
    }
}
