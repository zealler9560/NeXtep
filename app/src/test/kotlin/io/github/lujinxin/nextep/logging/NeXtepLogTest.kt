package io.github.lujinxin.nextep.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NeXtepLogTest {
    @Test fun theExportCarriesWhatAFailureSaid() {
        NeXtepLog.error("task_surface", "Presentation failed taskId=2", IllegalStateException("WM Shell task leash unavailable for taskId=2"))
        assertTrue(NeXtepLog.diagnostics().lines().last().endsWith(
            "error=IllegalStateException message=Presentation failed taskId=2 cause=WM Shell task leash unavailable for taskId=2"))
    }

    @Test fun linksAndIntentUrisAreMaskedWhileIdsAndComponentsStay() {
        assertEquals("open <uri> in slot=2", NeXtepLog.masked("open https://example.com/a?q=secret in slot=2"))
        assertEquals("pane -1|<uri>", NeXtepLog.masked("pane -1|intent:#Intent;action=android.intent.action.VIEW;end"))
        assertEquals("view intent:<uri>", NeXtepLog.masked("view intent:https://example.com/x"))
        val plain = "Transition started open main component=ComponentInfo{com.tencent.tim/com.tencent.mobileqq.activity.SplashActivity} " +
            "geometry=WorkspaceGeometry(screenWidth=1272, topHeight=563) at 08:31"
        assertEquals(plain, NeXtepLog.masked(plain))
    }
}
