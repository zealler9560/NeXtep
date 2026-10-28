package io.github.lujinxin.nextep.launcher

import android.content.Intent

internal object WorkspaceLauncherAnimationPolicy {
    fun shouldFinishClose(active: Boolean, animationsOff: Boolean, type: String?): Boolean =
        active && animationsOff && type in setOf("SWIPE_TO_HOME", "REMOTE_CLOSE_TO_HOME", "PREDICTIVE_BACK_TO_HOME")

    /** False includes springs and dynamic animators; cancel/end on the wrapper cancels instead. */
    fun finishClose(animation: Any) {
        val record = animation.javaClass.getMethod("getLauncherRecord").invoke(animation)
        record.javaClass.getMethod("endAnimatorSet", Boolean::class.javaPrimitiveType).invoke(record, false)
    }

    fun useStaticSource(active: Boolean, intent: Intent?): Boolean = active &&
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
        intent.component != null
}
