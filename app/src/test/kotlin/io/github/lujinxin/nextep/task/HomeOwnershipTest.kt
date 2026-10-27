package io.github.lujinxin.nextep.task

import io.github.lujinxin.nextep.display.VirtualDisplaySlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeOwnershipTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun colorOsLaunchersScaleTheirOwnDesktop() {
        assertTrue(MainTaskPresentationCoordinator(context).launcherOwnsHome("com.oplus.launcher"))
    }

    @Test fun aLauncherWithNoCompanionAnsweringLeavesTheDesktopToSystemUi() {
        assertFalse(MainTaskPresentationCoordinator(context).launcherOwnsHome("com.miui.home"))
    }

    @Test fun aPaneWithoutAnOutputLayerDoesNotMirror() {
        val slot = VirtualDisplaySlot(context, 0)
        assertFalse(slot.mirrorTask(42))
        assertFalse(slot.mirrorsTask(42))
        slot.clearMirror()
    }
}
