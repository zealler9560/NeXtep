package io.github.lujinxin.nextep.framework

import android.app.ActivityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskInfoCompatTest {
    private fun task(activityType: Int) = ActivityManager.RunningTaskInfo().also { info ->
        val configuration = info.javaClass.getField("configuration").get(info)
        val window = configuration.javaClass.getField("windowConfiguration").get(configuration)
        window.javaClass.getMethod("setActivityType", Int::class.javaPrimitiveType).invoke(window, activityType)
    }

    @Test fun theDesktopIsKnownByItsActivityTypeNotItsPackage() {
        assertTrue(TaskInfoCompat.isHome(task(activityType = 2)))
        assertFalse(TaskInfoCompat.isHome(task(activityType = 1)))
    }
}
