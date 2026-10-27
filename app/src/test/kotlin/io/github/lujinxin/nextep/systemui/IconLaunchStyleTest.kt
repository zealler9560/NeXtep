package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IconLaunchStyleTest {
    @Test fun colorOsMakersGetTheMeasuredPresetAndOthersTheGenericOne() {
        for (maker in listOf("OPPO", "OnePlus", "realme", "Realme")) assertSame(IconLaunchStyle.COLOR_OS, IconLaunchStyle.forDevice(maker))
        for (maker in listOf("Xiaomi", "samsung", "Google", "")) assertSame(IconLaunchStyle.GENERIC, IconLaunchStyle.forDevice(maker))
    }

    @Test fun theMeasuredCurveRisesMonotonicallyFromZeroToOne() {
        val curve = IconLaunchStyle.COLOR_OS.growCurve
        assertEquals(0f, curve.getInterpolation(0f), 0.001f)
        assertEquals(1f, curve.getInterpolation(1f), 0.001f)
        var last = 0f
        for (i in 1..100) {
            val p = curve.getInterpolation(i / 100f)
            assertTrue("progress fell at $i", p >= last - 0.001f)
            last = p
        }
        // Most of the way at the half-way point: the window front-loads its growth.
        assertTrue(curve.getInterpolation(0.5f) > 0.85f)
    }
}
