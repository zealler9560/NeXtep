package io.github.lujinxin.nextep.launcher

import android.content.ComponentName
import android.content.Intent
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceLauncherAnimationPolicyTest {
    @Test fun noAnimationRunsNativeCompletionWithoutCancellationOrDelay() {
        val events = mutableListOf<String>()
        var lastFrame = -1f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600L
            startDelay = 200L
            addUpdateListener { lastFrame = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) { events += "start" }
                override fun onAnimationEnd(animation: Animator) { events += "end" }
                override fun onAnimationCancel(animation: Animator) { events += "cancel" }
            })
        }
        assertTrue(WorkspaceLauncherAnimationPolicy.applyNoAnimation(animator, active = true, disabled = true))
        animator.start()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
        assertEquals(listOf("start", "end"), events)
        assertEquals(1f, lastFrame, 0f)
        assertFalse(animator.isRunning)
    }

    @Test fun nativeAnimationIsPreservedOutsideTheWorkspaceOrWithAnimationsEnabled() {
        for ((active, disabled) in listOf(false to true, true to false, false to false)) {
            val animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 600L; startDelay = 200L }
            assertFalse(WorkspaceLauncherAnimationPolicy.applyNoAnimation(animator, active, disabled))
            assertEquals(600L, animator.duration)
            assertEquals(200L, animator.startDelay)
        }
    }

    @Test fun onlyExplicitAppIconsInsideTheWorkspaceLoseTheNativeLaunchSource() {
        val app = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName("example.app", "example.app.Main"))
        assertTrue(WorkspaceLauncherAnimationPolicy.useStaticSource(true, app))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(false, app))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, Intent(app).setAction(Intent.ACTION_VIEW)))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, Intent(app).setComponent(null)))
        assertFalse(WorkspaceLauncherAnimationPolicy.useStaticSource(true, null))
    }
}
