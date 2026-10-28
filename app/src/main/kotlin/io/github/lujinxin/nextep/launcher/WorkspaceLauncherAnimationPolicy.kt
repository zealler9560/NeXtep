package io.github.lujinxin.nextep.launcher

import android.content.Intent
import android.animation.ValueAnimator

internal object WorkspaceLauncherAnimationPolicy {
    fun useStaticSource(active: Boolean, intent: Intent?): Boolean = active &&
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
        intent.component != null

    fun applyNoAnimation(animator: ValueAnimator, active: Boolean, disabled: Boolean): Boolean {
        if (!active || !disabled) return false
        // Keep native start/end listeners: they release the return animation's input interception.
        animator.startDelay = 0L
        animator.duration = 0L
        return true
    }
}
