package io.github.lujinxin.nextep.systemui

import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.WorkspaceControlMetrics
import kotlin.math.roundToInt

/** HyperOS can draw bar icons inside the band ColorOS leaves blank below its icons. */
internal object WorkspaceStatusBarCompat {
    val isHyperOs: Boolean by lazy {
        runCatching {
            val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            listOf("ro.mi.os.version.name", "ro.miui.ui.version.name")
                .any { !(get.invoke(null, it) as? String).isNullOrBlank() }
        }.getOrDefault(false)
    }

    fun keptGap(density: Float, hyperOs: Boolean = isHyperOs): Int =
        if (hyperOs) 0 else (WorkspaceControlMetrics.STATUS_KEEP_DP * density).roundToInt()

    private val shownTypesField by lazy {
        runCatching { WindowManager.LayoutParams::class.java.getField("forciblyShownTypes") }
            .onFailure { NeXtepLog.warn("workspace_status_bar", "Native bar visibility field unavailable", it) }
            .getOrNull()
    }

    /** The native transient owner shows the phone's bar without changing fullscreen app layout. */
    fun configure(params: WindowManager.LayoutParams, active: Boolean, hyperOs: Boolean = isHyperOs) {
        if (!hyperOs || params.title?.toString() != "NeXtepTopBar") return
        runCatching {
            val field = shownTypesField ?: return
            val before = field.getInt(params)
            val status = WindowInsets.Type.statusBars()
            val after = if (active) before or status else before and status.inv()
            if (before != after) {
                field.setInt(params, after)
                NeXtepLog.info("workspace_status_bar", "Physical status bar requested=$active types=$after")
            }
        }.onFailure { NeXtepLog.warn("workspace_status_bar", "Native bar visibility update unavailable", it) }
    }

    /** Only system bar/panel geometry, never view text or notification contents. */
    fun diagnostics(details: Boolean = false): String = runCatching {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getDeclaredMethod("getInstance").invoke(null)
        val views = globalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        views.toList().filterIsInstance<View>().mapNotNull { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@mapNotNull null
            if (params.type != WindowManager.LayoutParams.TYPE_STATUS_BAR &&
                params.type != 2040 && params.title?.toString() !in setOf("NeXtepTopBar", "NotificationShade", "ControlCenter")) return@mapNotNull null
            val position = IntArray(2).also(view::getLocationOnScreen)
            val insets = view.rootWindowInsets
            "type=${params.type} class=${view.javaClass.name} size=${view.width}x${view.height} xy=${position.joinToString(",")} " +
                "visible=${view.visibility} alpha=${view.alpha}/${params.alpha} " +
                "statusVisible=${insets?.isVisible(WindowInsets.Type.statusBars())} " +
                "statusTop=${insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top} " +
                "forced=${shownTypesField?.getInt(params)} flags=${params.flags} private=${params.privateFlagsCompat()} " +
                "requestedSize=${params.width},${params.height} requestedXY=${params.x},${params.y}" +
                if (details && params.type == WindowManager.LayoutParams.TYPE_STATUS_BAR) "\n${viewGeometry(view)}" else ""
        }.joinToString("\n").ifEmpty { "no status bar/panel roots" }
    }.getOrElse { "status bar diagnostics unavailable: ${it.javaClass.simpleName}" }

    private fun WindowManager.LayoutParams.privateFlagsCompat(): Int? = runCatching {
        javaClass.getField("privateFlags").getInt(this)
    }.getOrNull()

    /** Bounded geometry only: never read text, descriptions or notification data. */
    internal fun viewGeometry(root: View): String {
        val lines = mutableListOf<String>()
        fun visit(view: View, depth: Int) {
            if (lines.size >= 48 || depth > 5) return
            val at = IntArray(2).also(view::getLocationOnScreen)
            val rect = android.graphics.Rect()
            val globallyVisible = view.getGlobalVisibleRect(rect)
            lines += "depth=$depth class=${view.javaClass.name} id=${view.id} visible=${view.visibility} shown=${view.isShown} " +
                "alpha=${view.alpha} xy=${at.joinToString(",")} size=${view.width},${view.height} " +
                "translation=${view.translationX},${view.translationY} scale=${view.scaleX},${view.scaleY} " +
                "global=$globallyVisible rect=$rect"
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        return lines.joinToString("\n")
    }
}
