package io.github.lujinxin.nextep.framework

import android.graphics.Rect
import android.animation.ValueAnimator
import android.view.SurfaceControl
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.WorkspaceNavigationInsets
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [TaskSurfaceRestoreTest.LeashShadow::class,
    TaskSurfaceRestoreTest.TransactionShadow::class])
class TaskSurfaceRestoreTest {
    private val task = 142
    private val token = Any()
    private val other = Any()
    private val geometry = WorkspaceGeometry(1216, 2640, 543, 258, SidebarSide.RIGHT,
        navigationInsets = WorkspaceNavigationInsets(bottom = 1), statusInset = 87)

    @Suppress("UNCHECKED_CAST")
    private fun attach(): SurfaceControl {
        val leash = org.robolectric.util.ReflectionHelpers.newInstance(SurfaceControl::class.java)
        TaskSurfaceCompat.observeTaskState(task,
            TaskInfoCompat.WindowState(0, Rect(0, 0, 1216, 2640), 1, 1, 476, null))
        val leashes = TaskSurfaceCompat.javaClass.getDeclaredField("observedLeashes")
            .apply { isAccessible = true }.get(TaskSurfaceCompat) as MutableMap<Int, Any>
        leashes[task] = leash
        TaskSurfaceCompat.present(task, geometry).getOrThrow()
        return leash
    }

    private fun nativeFinish(leash: SurfaceControl, scale: Float) {
        SurfaceControl.Transaction().use { finish ->
            finish.setScale(leash, scale, scale).setPosition(leash, 0f, 630f)
                .setCrop(leash, Rect(0, 87, 1216, 2640))
            TaskSurfaceCompat.interceptTransactionApply(finish) { finish.apply() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun endAnimation() {
        val animators = TaskSurfaceCompat.javaClass.getDeclaredField("presentationAnimators")
            .apply { isAccessible = true }.get(TaskSurfaceCompat) as Map<Int, ValueAnimator>
        animators[task]?.end()
    }

    @After fun clear() {
        TaskSurfaceCompat.finishTransition(token)
        TaskSurfaceCompat.finishTransition(other)
        TaskSurfaceCompat.restore(task).getOrThrow()
        TaskSurfaceCompat.forgetTaskSurface(task)
    }

    @Test fun aCancelledCornerNavigationCannotRestoreTheOldPaneAfterExit() {
        val leash = attach()
        TaskSurfaceCompat.observeTransition(token, setOf(task))
        TaskSurfaceCompat.restore(task).getOrThrow()
        nativeFinish(leash, .7873f)
        assertEquals(1f, TransactionShadow.scale, 0f)
        assertEquals(0f, TransactionShadow.y, 0f)
        assertNull(TransactionShadow.crop)
        TaskSurfaceCompat.finishTransition(token)
        // Once the cancelled navigation has finished, normal system animations own the task.
        nativeFinish(leash, .6f)
        assertEquals(.6f, TransactionShadow.scale, 0f)
    }

    @Test fun overlappingNativeTransitionsBothFinishBeforeOwnershipEnds() {
        val leash = attach()
        TaskSurfaceCompat.observeTransition(token, setOf(task))
        TaskSurfaceCompat.observeTransition(other, setOf(task))
        TaskSurfaceCompat.restore(task).getOrThrow()
        TaskSurfaceCompat.finishTransition(token)
        nativeFinish(leash, .7873f)
        assertEquals(1f, TransactionShadow.scale, 0f)
        TaskSurfaceCompat.finishTransition(other)
        nativeFinish(leash, .6f)
        assertEquals(.6f, TransactionShadow.scale, 0f)
    }

    @Test fun theExitAnimationEndingBeforeRecentsDoesNotGiveUpTheFullscreenFrame() {
        val leash = attach()
        TaskSurfaceCompat.observeTransition(token, setOf(task))
        TaskSurfaceCompat.restore(task, animated = true).getOrThrow()
        endAnimation()
        nativeFinish(leash, .7873f)
        assertEquals(1f, TransactionShadow.scale, 0f)
        assertNull(TransactionShadow.crop)
        TaskSurfaceCompat.finishTransition(token)
        nativeFinish(leash, .6f)
        assertEquals(.6f, TransactionShadow.scale, 0f)
    }

    @Test fun reenteringBeforeTheOldTransitionEndsKeepsTheNewPane() {
        val leash = attach()
        TaskSurfaceCompat.observeTransition(token, setOf(task))
        TaskSurfaceCompat.restore(task).getOrThrow()
        TaskSurfaceCompat.present(task, geometry).getOrThrow()
        endAnimation()
        TaskSurfaceCompat.finishTransition(token)
        nativeFinish(leash, 1f)
        assertEquals(geometry.mainScale, TransactionShadow.scale, .0001f)
    }

    @Test fun exitingWithoutANativeTransitionReleasesTheLeashImmediately() {
        val leash = attach()
        TaskSurfaceCompat.restore(task).getOrThrow()
        nativeFinish(leash, .6f)
        assertEquals(.6f, TransactionShadow.scale, 0f)
    }

    @Test fun anUnrelatedTransitionDoesNotHoldTheRestoredTask() {
        val leash = attach()
        TaskSurfaceCompat.observeTransition(token, setOf(task + 1))
        TaskSurfaceCompat.restore(task).getOrThrow()
        nativeFinish(leash, .6f)
        assertEquals(.6f, TransactionShadow.scale, 0f)
    }

    @Implements(SurfaceControl::class)
    class LeashShadow {
        @Implementation fun isValid() = true
    }

    @Implements(SurfaceControl.Transaction::class)
    class TransactionShadow {
        @RealObject lateinit var real: SurfaceControl.Transaction
        @Implementation fun setScale(surface: SurfaceControl, x: Float, y: Float): SurfaceControl.Transaction {
            scale = x
            return real
        }
        @Implementation fun setCrop(surface: SurfaceControl, value: Rect?): SurfaceControl.Transaction {
            crop = value?.let(::Rect)
            return real
        }
        @Implementation fun setPosition(surface: SurfaceControl, x: Float, value: Float): SurfaceControl.Transaction {
            y = value
            return real
        }
        @Implementation fun apply() {}
        @Implementation fun close() {}
        companion object {
            var scale = 0f
            var y = 0f
            var crop: Rect? = null
        }
    }
}
