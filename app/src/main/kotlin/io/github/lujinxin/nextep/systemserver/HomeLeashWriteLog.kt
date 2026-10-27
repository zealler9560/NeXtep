package io.github.lujinxin.nextep.systemserver

import android.content.Context
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostics only: while the workspace shows, logs each distinct caller in system_server that writes the
 * position or matrix of the desktop's task layers. On HyperOS the desktop went full size in the main pane
 * while its pages were swiped, and no SystemUI write explained it.
 */
internal object HomeLeashWriteLog {
    private val methods = ConcurrentHashMap<String, Optional<Method>>()
    /** The desktop's task layers on display 0 while the workspace shows; empty otherwise. */
    @Volatile private var watched: List<SurfaceControl> = emptyList()
    @Volatile private var context: Context? = null
    private val callers = ConcurrentHashMap.newKeySet<String>()

    fun install(module: XposedModule, loader: ClassLoader) {
        module.hook(loader.loadClass("com.android.server.wm.DisplayContent").getDeclaredMethod("prepareSurfaces")
            .apply { isAccessible = true }).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching { chain.thisObject?.let(::track) }
                return result
            }
        })
        val floatType = Float::class.javaPrimitiveType
        val writers = SurfaceControl.Transaction::class.java.declaredMethods.filter { method ->
            val types = method.parameterTypes
            types.firstOrNull() == SurfaceControl::class.java && when (method.name) {
                "setScale", "setPosition" -> types.size == 3 && types[1] == floatType && types[2] == floatType
                "setMatrix" -> types.size == 5 && types.drop(1).all { it == floatType } ||
                    types.size == 3 && types[1] == android.graphics.Matrix::class.java
                else -> false
            }
        }
        writers.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val layers = watched
                    if (layers.isNotEmpty()) {
                        val surface = chain.args[0]
                        if (layers.any { it === surface }) runCatching { note(method.name, chain.args) }
                    }
                    return chain.proceed()
                }
            })
        }
        NeXtepLog.info("home_leash_write", "Watching ${writers.size} writers of the desktop's task layers")
    }

    private fun track(display: Any) {
        if (call(display, "getDisplayId") != 0) return
        val context = context ?: (field(display, "mWmService")?.let { field(it, "mContext") } as? Context)
            ?.also { context = it } ?: return
        if (!SystemServerWorkspaceBridge.observedActive(context)) {
            if (watched.isNotEmpty()) {
                watched = emptyList()
                callers.clear()
            }
            return
        }
        val area = call(display, "getDefaultTaskDisplayArea") ?: return
        val home = call(area, "getRootHomeTask") ?: return
        val layers = listOfNotNull(home, call(home, "getRootTask"), call(home, "getTopMostTask"))
            .mapNotNull { call(it, "getSurfaceControl") as? SurfaceControl }.distinct()
        if (layers != watched) watched = layers
    }

    private fun note(name: String, args: List<Any?>) {
        val caller = Throwable().stackTrace.asSequence()
            .filterNot { it.className.startsWith("io.github.lujinxin") || it.className.startsWith("io.github.libxposed") ||
                it.className.startsWith("android.view.SurfaceControl") || it.className.startsWith("java.lang.reflect") ||
                it.className.startsWith("org.lsposed") || it.className.startsWith("LSPHooker") }
            .take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        // One line per distinct caller per workspace session; layout passes write every frame.
        if (callers.size >= MAX_CALLERS || !callers.add("$name $caller")) return
        NeXtepLog.info("home_leash_write", "$name ${args.drop(1).joinToString(",")} on ${args[0]} by $caller")
    }

    private fun field(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.firstOrNull()
        ?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String): Any? = methods.getOrPut(target.javaClass.name + "." + name) {
        Optional.ofNullable(generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true })
    }.orElse(null)?.invoke(target)

    private const val MAX_CALLERS = 24
}
