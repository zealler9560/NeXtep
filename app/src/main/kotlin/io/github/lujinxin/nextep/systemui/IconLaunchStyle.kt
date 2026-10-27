package io.github.lujinxin.nextep.systemui

import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * How the icon launch looks: the launcher's own open differs per ROM, so the overlay animates the
 * same way everywhere and only these numbers change. A ROM without a measured preset gets [GENERIC].
 *
 * The flight has two phases. First the icon lifts in place ([liftMs], to [liftScale] times its
 * size). Then the plate grows into the pane over [growMs] along [growCurve]: from the lifted icon
 * when [popFraction] is 0, or popping in at that fraction of the pane's size, centred [popBias] of
 * the way from the icon to the pane. The icon fades between [iconFadeStart] and [iconFadeEnd] of the
 * grow phase; with [iconGrows] it scales with the plate until then.
 */
class IconLaunchStyle(
    val liftMs: Long,
    val liftScale: Float,
    val popFraction: Float,
    val popBias: Float,
    val growMs: Long,
    val growCurve: Interpolator,
    val iconFadeStart: Float,
    val iconFadeEnd: Float,
    val iconGrows: Boolean,
    /** The plate takes the app's splash background, as the system's own starting window does. */
    val plateFromSplash: Boolean,
) {
    val totalMs: Long get() = liftMs + growMs

    companion object {
        val GENERIC = IconLaunchStyle(
            liftMs = 0L, liftScale = 1f, popFraction = 0f, popBias = 0f,
            growMs = 400L, growCurve = PathInterpolator(0.2f, 0f, 0f, 1f),
            iconFadeStart = 0.6f, iconFadeEnd = 0.95f, iconGrows = true, plateFromSplash = false,
        )

        /**
         * Measured on ColorOS 17 (OPPO PME110) at 60 fps: the icon grows to about 1.6x over 70 ms,
         * then the starting window pops in at 57% of the screen, centred about a third of the way
         * from the icon to the screen centre, and reaches full size over ~320 ms with a long tail.
         */
        val COLOR_OS = IconLaunchStyle(
            liftMs = 70L, liftScale = 1.6f, popFraction = 0.57f, popBias = 0.3f,
            growMs = 320L, growCurve = measuredCurve(
                0.052f to 0.162f, 0.104f to 0.376f, 0.156f to 0.477f, 0.208f to 0.569f, 0.260f to 0.680f,
                0.313f to 0.741f, 0.365f to 0.792f, 0.417f to 0.853f, 0.469f to 0.883f, 0.521f to 0.904f,
                0.573f to 0.934f, 0.625f to 0.944f, 0.677f to 0.964f, 0.729f to 0.975f, 0.833f to 0.985f,
                0.938f to 0.995f,
            ),
            iconFadeStart = 0f, iconFadeEnd = 0.25f, iconGrows = false, plateFromSplash = true,
        )

        fun forDevice(manufacturer: String = Build.MANUFACTURER): IconLaunchStyle =
            if (manufacturer.lowercase() in setOf("oppo", "oneplus", "realme")) COLOR_OS else GENERIC

        /** A progress curve through sampled (time, progress) points, both normalised to 0..1. */
        fun measuredCurve(vararg points: Pair<Float, Float>): Interpolator = PathInterpolator(Path().apply {
            moveTo(0f, 0f)
            points.forEach { (t, p) -> lineTo(t, p) }
            lineTo(1f, 1f)
        })

        /**
         * The colour the app's own starting window would show: its splash background, else its window
         * background when that is a plain colour. Null when the app declares neither.
         */
        fun splashColor(context: Context, component: ComponentName): Int? = runCatching {
            val info = context.packageManager.getActivityInfo(component, 0)
            val themeRes = info.themeResource.takeIf { it != 0 } ?: info.applicationInfo.theme
            if (themeRes == 0) return null
            val appContext = context.createPackageContext(component.packageName, Context.CONTEXT_IGNORE_SECURITY)
            val theme = appContext.resources.newTheme().apply { applyStyle(themeRes, true) }
            val attributes = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenBackground, android.R.attr.windowBackground))
            try {
                val splash = attributes.getColor(0, 0)
                if (splash != 0) return splash
                (attributes.getDrawable(1) as? ColorDrawable)?.color
            } finally {
                attributes.recycle()
            }
        }.getOrNull()
    }
}
