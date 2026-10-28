package io.github.lujinxin.nextep.launcher

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCloseAnimationTest {
    @Test fun onlyHomeReturnsFinishWhenWorkspaceAndSwitchAreEnabled() {
        val closes = listOf("SWIPE_TO_HOME", "REMOTE_CLOSE_TO_HOME", "PREDICTIVE_BACK_TO_HOME")
        for (type in closes) {
            assertTrue(WorkspaceLauncherAnimationPolicy.shouldFinishClose(true, true, type))
            assertFalse(WorkspaceLauncherAnimationPolicy.shouldFinishClose(false, true, type))
            assertFalse(WorkspaceLauncherAnimationPolicy.shouldFinishClose(true, false, type))
        }
        for (type in listOf(null, "LAUNCH_FROM_HOME", "SWIPE_TO_RECENTS", "SWIPE_TO_CAPSULE", "UNKNOWN")) {
            assertFalse(WorkspaceLauncherAnimationPolicy.shouldFinishClose(true, true, type))
        }
    }

    class Record {
        var finished: Boolean? = null
        fun endAnimatorSet(skipDynamics: Boolean) { finished = skipDynamics }
        fun cancelAnimatorSet(unused: Boolean) { error("Must finish, never cancel") }
    }
    class Animation {
        val record = Record()
        fun getLauncherRecord() = record
        fun end(unused: Boolean) { error("Wrapper end cancels the OEM record") }
    }
    @Test fun finishesNativeRecordIncludingDynamicAnimationsWithoutCancelling() {
        val animation = Animation()
        WorkspaceLauncherAnimationPolicy.finishClose(animation)
        assertEquals(false, animation.record.finished)
    }
}
