package io.github.lujinxin.nextep.framework

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntryExpectationTest {
    private val geometry = WorkspaceGeometry(1216, 2640, 562, 258, SidebarSide.RIGHT)
    private val icon = Rect(500, 1400, 700, 1600)

    private fun appeared(taskId: Int, packageName: String) = ActivityManager.RunningTaskInfo().apply {
        this.taskId = taskId
        baseIntent = Intent().setComponent(ComponentName(packageName, "$packageName.Main"))
    }

    @Test fun theExpectedPackagesTaskGetsItsFirstFrameOnceOthersDoNot() {
        TaskSurfaceCompat.expectEntry("example.app", icon, geometry)
        TaskSurfaceCompat.observeTaskInfo(appeared(41, "other.app"), Any())
        assertFalse(TaskSurfaceCompat.hasEntryPrepared(41))
        TaskSurfaceCompat.observeTaskInfo(appeared(42, "example.app"), Any())
        assertTrue(TaskSurfaceCompat.hasEntryPrepared(42))
        // Used up: a second task of the same package appears plainly.
        TaskSurfaceCompat.observeTaskInfo(appeared(43, "example.app"), Any())
        assertFalse(TaskSurfaceCompat.hasEntryPrepared(43))
        TaskSurfaceCompat.forgetTaskSurface(42)
        assertFalse(TaskSurfaceCompat.hasEntryPrepared(42))
    }

    @Test fun anEmptyIconRectangleAndATaskWithoutALeashPrepareNothing() {
        TaskSurfaceCompat.expectEntry("example.app", Rect(), geometry)
        TaskSurfaceCompat.observeTaskInfo(appeared(44, "example.app"), Any())
        assertFalse(TaskSurfaceCompat.hasEntryPrepared(44))
        TaskSurfaceCompat.expectEntry("example.app", icon, geometry)
        TaskSurfaceCompat.observeTaskInfo(appeared(45, "example.app"), null)
        assertFalse(TaskSurfaceCompat.hasEntryPrepared(45))
        TaskSurfaceCompat.observeTaskInfo(appeared(45, "example.app"), Any())
        assertTrue(TaskSurfaceCompat.hasEntryPrepared(45))
        TaskSurfaceCompat.forgetTaskSurface(45)
    }
}
