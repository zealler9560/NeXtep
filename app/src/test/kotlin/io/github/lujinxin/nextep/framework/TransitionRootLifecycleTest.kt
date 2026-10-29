package io.github.lujinxin.nextep.framework

import android.app.ActivityManager
import android.graphics.Point
import android.graphics.Rect
import android.view.SurfaceControl
import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [TaskSurfaceRestoreTest.LeashShadow::class, TransitionRootLifecycleTest.TransactionShadow::class])
class TransitionRootLifecycleTest {
    private val task = 843
    private val token = Any()
    private val geometry = WorkspaceGeometry(1216, 2640, 543, 258, SidebarSide.RIGHT, statusInset = 87)
    private fun surface() = ReflectionHelpers.newInstance(SurfaceControl::class.java)
    class Change(private val leash: SurfaceControl, private val task: ActivityManager.RunningTaskInfo? = null) {
        fun getEndDisplayId() = 0
        fun getLeash() = leash
        fun getTaskInfo() = task
        fun getFlags() = 0
    }
    class Root(private val leash: SurfaceControl) {
        fun getDisplayId() = 0
        fun getLeash() = leash
        fun getOffset() = Point(0, 0)
    }
    class Info(private val type: Int, private val changes: List<Change>, private val root: Root) {
        fun getType() = type
        fun getChanges() = changes
        fun getRootCount() = 1
        fun getRoot(index: Int) = root
    }
    @Suppress("UNCHECKED_CAST")
    private fun attach(): SurfaceControl {
        val leash = surface()
        TaskSurfaceCompat.observeTaskState(task, TaskInfoCompat.WindowState(0, Rect(0, 0, 1216, 2640), 1, 1, 476, null))
        val leashes = TaskSurfaceCompat.javaClass.getDeclaredField("observedLeashes").apply { isAccessible = true }
            .get(TaskSurfaceCompat) as MutableMap<Int, Any>
        leashes[task] = leash
        TaskSurfaceCompat.present(task, geometry).getOrThrow()
        val animators = TaskSurfaceCompat.javaClass.getDeclaredField("presentationAnimators").apply { isAccessible = true }
            .get(TaskSurfaceCompat) as Map<Int, android.animation.ValueAnimator>
        animators[task]?.end()
        return leash
    }
    private fun commit(transaction: SurfaceControl.Transaction) = TaskSurfaceCompat.interceptTransactionApply(transaction) { transaction.apply() }
    @After fun clear() {
        TaskSurfaceCompat.dropTransitionRoot(token)
        TaskSurfaceCompat.restore(task).getOrThrow()
        TaskSurfaceCompat.forgetTaskSurface(task)
        TaskSurfaceCompat.desktopScaledHere = false
        TransactionShadow.frames.clear()
    }
    @Test fun recentsOwnsTheCropWhileTheRootFitsThePaneAndFinishRestoresIt() {
        val app = attach()
        val root = surface()
        SurfaceControl.Transaction().use { transaction ->
            assertTrue(TaskSurfaceCompat.liftIntoTransitionRoot(token, Info(1021, listOf(Change(app)), Root(root)), transaction))
            commit(transaction)
            assertEquals(geometry.mainScale, TransactionShadow.frames[root]!!.scaleX, .0001f)
            assertEquals(1f, TransactionShadow.frames[app]!!.scaleX, 0f)
            assertNull(TransactionShadow.frames[app]!!.crop)
            TaskSurfaceCompat.dropTransitionRoot(token)
            commit(transaction)
            assertEquals(geometry.mainScale, TransactionShadow.frames[app]!!.scaleX, .0001f)
            assertEquals(87, TransactionShadow.frames[app]!!.crop!!.top)
        }
    }
    @Test fun colorOsHomeIsNotScaledTwiceAndItsCounterScaleIsRemovedAtFinish() {
        val app = attach()
        val home = surface()
        val info = ActivityManager.RunningTaskInfo()
        val configuration = info.javaClass.getField("configuration").get(info)
        val window = configuration.javaClass.getField("windowConfiguration").get(configuration)
        window.javaClass.getMethod("setActivityType", Int::class.javaPrimitiveType).invoke(window, 2)
        SurfaceControl.Transaction().use { transaction ->
            assertTrue(TaskSurfaceCompat.liftIntoTransitionRoot(token,
                Info(1021, listOf(Change(app), Change(home, info)), Root(surface())), transaction))
            commit(transaction)
            assertEquals(1f / geometry.mainScale, TransactionShadow.frames[home]!!.scaleX, .0001f)
            TaskSurfaceCompat.dropTransitionRoot(token)
            commit(transaction)
            assertEquals(1f, TransactionShadow.frames[home]!!.scaleX, 0f)
            assertEquals(0f, TransactionShadow.frames[home]!!.positionY, 0f)
        }
    }

    @Test fun anUnrelatedColorOsTransitionDoesNotLift() {
        val app = attach()
        SurfaceControl.Transaction().use { transaction ->
            assertFalse(TaskSurfaceCompat.liftIntoTransitionRoot(token, Info(1, listOf(Change(app)), Root(surface())), transaction))
            commit(transaction)
            assertEquals(geometry.mainScale, TransactionShadow.frames[app]!!.scaleX, .0001f)
        }
    }
    @Test fun hyperOsKeepsItsStatusBarCropAndDeclinedTransitionRestoresRoot() {
        TaskSurfaceCompat.desktopScaledHere = true
        val app = attach()
        val root = surface()
        SurfaceControl.Transaction().use { transaction ->
            assertTrue(TaskSurfaceCompat.liftIntoTransitionRoot(token, Info(1, listOf(Change(app)), Root(root)), transaction))
            commit(transaction)
            assertEquals(87, TransactionShadow.frames[app]!!.crop!!.top)
            TaskSurfaceCompat.dropTransitionRoot(token, transaction)
            assertEquals(1f, TransactionShadow.frames[root]!!.scaleX, 0f)
            commit(transaction)
            assertEquals(geometry.mainScale, TransactionShadow.frames[app]!!.scaleX, .0001f)
        }
    }
    @Implements(SurfaceControl.Transaction::class)
    class TransactionShadow {
        @RealObject lateinit var real: SurfaceControl.Transaction
        @Implementation fun setScale(surface: SurfaceControl, x: Float, y: Float): SurfaceControl.Transaction {
            frames[surface] = frame(surface).copy(scaleX = x, scaleY = y); return real
        }
        @Implementation fun setPosition(surface: SurfaceControl, x: Float, y: Float): SurfaceControl.Transaction {
            frames[surface] = frame(surface).copy(positionX = x, positionY = y); return real
        }
        @Implementation fun setCrop(surface: SurfaceControl, crop: Rect?): SurfaceControl.Transaction {
            frames[surface] = frame(surface).copy(crop = crop?.let(::Rect)); return real
        }
        @Implementation fun apply() {}
        @Implementation fun close() {}
        companion object {
            internal val frames = java.util.IdentityHashMap<SurfaceControl, TaskSurfaceCompat.SurfaceFrame>()
            internal fun frame(surface: SurfaceControl) = frames[surface] ?: TaskSurfaceCompat.SurfaceFrame(1f, 1f, 0f, 0f)
        }
    }
}
