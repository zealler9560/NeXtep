package io.github.lujinxin.nextep.systemui

import android.view.WindowInsets
import android.view.WindowManager
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile
import io.github.lujinxin.nextep.workspace.WorkspaceNavigationInsets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36, 37])
class WorkspaceStatusBarCompatTest {
    @Test fun hyperOsCropsTheWholeBarWhileColorOsKeepsItsExistingGap() {
        assertEquals(0, WorkspaceStatusBarCompat.keptGap(2.75f, true))
        assertEquals(44, WorkspaceStatusBarCompat.keptGap(2.75f, false))
        assertEquals(48, WorkspaceStatusBarCompat.keptGap(2.975f, false))
        val old = WorkspaceLayoutProfile.create(1080, 2400, 440, WorkspaceNavigationInsets(), statusBar = 50)
        val current = WorkspaceLayoutProfile.create(1080, 2400, 440, WorkspaceNavigationInsets(),
            statusBar = 94, statusBarGap = 0)
        assertEquals(94, old.statusBarHeight)
        assertEquals(94, current.statusBarHeight)
        assertEquals(94, current.slotCrop)
        assertEquals(94, current.geometry.availableTop)
        assertNull(WorkspaceLayoutProfile.decode(old.encode(), 94, 0))
        assertEquals(current, WorkspaceLayoutProfile.decode(current.encode(), 94, 0))
        // Deriving another layout must not add the old 16dp inset back to the virtual display.
        for (derived in listOf(current.withControlAtBottom(true), current.withWideSlot(0),
            current.withWideSlot(0).withWideSlot(null), current.withControlAtBottom(true).withControlAtBottom(false))) {
            assertEquals(94, derived.statusBarHeight)
            assertEquals(0, derived.statusBarGap)
        }
    }

    @Test fun nativeStatusBarRequestIsReleasedAndOtherBarFlagsArePreserved() {
        val params = WindowManager.LayoutParams().apply { title = "NeXtepTopBar" }
        val field = WindowManager.LayoutParams::class.java.getField("forciblyShownTypes")
        val nav = WindowInsets.Type.navigationBars()
        val status = WindowInsets.Type.statusBars()
        field.setInt(params, nav)
        WorkspaceStatusBarCompat.configure(params, true, true)
        assertEquals(nav or status, field.getInt(params))
        WorkspaceStatusBarCompat.configure(params, false, true)
        assertEquals(nav, field.getInt(params))
        WorkspaceStatusBarCompat.configure(params, true, true)
        assertEquals(nav or status, field.getInt(params))
    }

    @Test fun colorOsAndOtherWindowsAreNotModified() {
        val field = WindowManager.LayoutParams::class.java.getField("forciblyShownTypes")
        val params = WindowManager.LayoutParams().apply { title = "NeXtepTopBar" }
        WorkspaceStatusBarCompat.configure(params, true, false)
        assertEquals(0, field.getInt(params))
        params.title = "NeXtepSidebar"
        WorkspaceStatusBarCompat.configure(params, true, true)
        assertEquals(0, field.getInt(params))
    }
}
