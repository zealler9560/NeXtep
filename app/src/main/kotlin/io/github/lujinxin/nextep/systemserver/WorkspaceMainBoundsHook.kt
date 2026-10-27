package io.github.lujinxin.nextep.systemserver

import android.content.res.Configuration
import android.graphics.Rect
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge

/**
 * AOSP resolves a fullscreen task's override bounds to empty, "fill the parent"; ColorOS keeps them.
 * Under a wide picture the main app is laid out at the shortened canvas through exactly such
 * bounds, so on HyperOS it stayed at full height and every exchange into the main pane timed out
 * waiting for them. Keep that one rectangle, on the main display, while the workbench shows.
 */
internal object WorkspaceMainBoundsHook {
    private val windowConfiguration by lazy { Configuration::class.java.getField("windowConfiguration") }
    @Volatile private var reported = false
    @Volatile private var reportedKept = false

    fun install(module: XposedModule, loader: ClassLoader) {
        val method = loader.loadClass("com.android.server.wm.Task").declaredMethods.single {
            it.name.startsWith("resolveLeaf") && it.name.endsWith("OverrideConfigs")
        }.apply { isAccessible = true }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching { chain.thisObject?.let(::keep) }.onFailure {
                    if (!reported) {
                        reported = true
                        NeXtepLog.warn("workspace_main_bounds", "Main pane bounds unavailable", it)
                    }
                }
                return result
            }
        })
        NeXtepLog.info("workspace_main_bounds", "Installed shortened main canvas bounds for ${method.name}")
    }

    private fun keep(task: Any) {
        // RAM only: configuration resolution runs inside the WM lock.
        val geometry = SystemServerWorkspaceBridge.observedActiveGeometry() ?: return
        if (geometry.mainCanvasHeight <= 0) return
        val requested = call(task, "getRequestedOverrideBounds") as? Rect ?: return
        if (requested != Rect(0, geometry.mainTaskTop, geometry.screenWidth, geometry.screenHeight)) return
        if (call(task, "getDisplayId") != 0 || call(task, "getActivityType") == ACTIVITY_TYPE_HOME) return
        val resolved = call(windowConfiguration.get(call(task, "getResolvedOverrideConfiguration")), "getBounds") as Rect
        if (!resolved.isEmpty) return
        resolved.set(requested)
        if (!reportedKept) {
            reportedKept = true
            NeXtepLog.info("workspace_main_bounds", "Kept the shortened main canvas $requested")
        }
    }

    private val methods = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, java.lang.reflect.Method>()

    /** Task.getDisplayId is package-private; public lookup alone failed on every call. */
    private fun call(target: Any, name: String): Any? = methods.getOrPut(target.javaClass to name) {
        generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .firstNotNullOf { type -> type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 } }
            .apply { isAccessible = true }
    }.invoke(target)

    private const val ACTIVITY_TYPE_HOME = 2
}
