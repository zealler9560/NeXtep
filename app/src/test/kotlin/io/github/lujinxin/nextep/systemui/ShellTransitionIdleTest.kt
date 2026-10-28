package io.github.lujinxin.nextep.systemui

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShellTransitionIdleTest {
    class NativeTransitions(var busy: Boolean = true, var fail: Boolean = false) {
        val shellWork = ArrayDeque<Runnable>()
        val idleWork = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        fun getMainExecutor(): Executor = Executor { shellWork.addLast(it) }
        fun runOnIdle(callback: Runnable) {
            if (fail) error("Unsupported native callback")
            if (busy) idleWork.addLast(callback) else callback.run()
        }
        fun finishHome() {
            events += "home committed"
            busy = false
            while (idleWork.isNotEmpty()) idleWork.removeFirst().run()
        }
    }

    @Test fun newAppStartsOnlyAfterHomeFinishHasCommittedAndOnTheUiThread() {
        val native = NativeTransitions()
        ShellTransitionLog.attachTransitions(native)
        assertTrue(ShellTransitionLog.runOnIdle { native.events += "new app" })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(native.events.isEmpty())
        native.shellWork.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(native.events.isEmpty())
        native.finishHome()
        assertEquals(listOf("home committed"), native.events)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("home committed", "new app"), native.events)
    }

    @Test fun idleShellDoesNotImposeAnAnimationDelay() {
        val native = NativeTransitions(busy = false)
        ShellTransitionLog.attachTransitions(native)
        assertTrue(ShellTransitionLog.runOnIdle { native.events += "new app" })
        native.shellWork.removeFirst().run()
        assertTrue(native.idleWork.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("new app"), native.events)
    }

    @Test fun unsupportedNativeCallbackDoesNotStrandTheUsersLaunch() {
        val native = NativeTransitions(fail = true)
        ShellTransitionLog.attachTransitions(native)
        assertTrue(ShellTransitionLog.runOnIdle { native.events += "fallback" })
        native.shellWork.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("fallback"), native.events)
    }
}
