package io.github.lujinxin.nextep.display

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.view.Display
import android.view.SurfaceView
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat

/**
 * One line per pane for the diagnostics export, splitting a black pane into its three stages:
 * the app (is its task on the pane's display, visible, sleeping), the display (power state), and
 * the task's own layer (does the app draw at all) against our output layer (does it receive it).
 */
internal object SlotDiagnostics {
    /** Never throws: it runs in SystemUI's output check and export. */
    fun describe(context: Context, index: Int, display: Display?, taskId: Int?, output: SurfaceView, flags: String): String =
        runCatching { describeOrThrow(context, index, display, taskId, output, flags) }.getOrElse { "slot=$index describe failed $it" }

    private fun describeOrThrow(context: Context, index: Int, display: Display?, taskId: Int?, output: SurfaceView, flags: String): String {
        val tasks = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(ActivityManager::class.java).getRunningTasks(40)
        }.getOrDefault(emptyList())
        return buildString {
            append("slot=").append(index).append(' ').append(flags)
            if (display == null) append(" display=none") else {
                append(" display=").append(display.displayId).append(" state=").append(display.state)
                append(" interactive=").append(interactive(context, display.displayId))
                append(" onDisplay=[")
                append(tasks.filter { field(it, "displayId") == display.displayId }.joinToString(" ", transform = ::task))
                append(']')
            }
            append(" output=").append(output(output))
            if (taskId != null) {
                val info = tasks.firstOrNull { it.taskId == taskId }
                append(" expected=").append(taskId)
                append(" at=").append(info?.let { field(it, "displayId") } ?: "gone")
                append(" layer=").append(layer(taskId))
            }
        }
    }

    private fun task(info: ActivityManager.RunningTaskInfo) =
        "#${info.taskId}:${info.topActivity?.shortClassName}" +
            " vis=${field(info, "isVisible")} req=${field(info, "isVisibleRequested")}" +
            " sleep=${field(info, "isSleeping")} run=${field(info, "isRunning")}"

    private fun output(view: SurfaceView) =
        "att=${view.isAttachedToWindow} shown=${view.isShown} a=${view.alpha} ${view.width}x${view.height}" +
            " win=${view.windowVisibility} surface=${view.holder.surface.isValid} layer=${view.surfaceControl?.isValid}"

    /** The task's own layers, captured apart from the pane: content here and none in the output is a display problem. */
    private fun layer(taskId: Int): String {
        val bitmap = TaskSurfaceCompat.capturePreview(taskId, scale = 0.05f) ?: return "none"
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            val luma = pixels.map { (android.graphics.Color.red(it) * 3 + android.graphics.Color.green(it) * 6 + android.graphics.Color.blue(it)) / 10 }
            val alpha = pixels.maxOf { android.graphics.Color.alpha(it) }
            "${bitmap.width}x${bitmap.height} mean=${luma.average().toInt()} spread=${luma.max() - luma.min()} alpha=$alpha"
        } finally { bitmap.recycle() }
    }

    private fun interactive(context: Context, displayId: Int): Any? = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        power.javaClass.getMethod("isInteractive", Int::class.javaPrimitiveType).invoke(power, displayId)
    }.getOrDefault("?")

    private fun field(target: Any, name: String): Any? = runCatching {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { return@runCatching it.apply { isAccessible = true }.get(target) }
            type = type.superclass
        }
        "?"
    }.getOrDefault("?")
}
