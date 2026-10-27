package io.github.lujinxin.nextep.systemui

import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Proxy

/**
 * HyperOS runs its desktop's open and close animations through Shell's RecentsTransitionHandler,
 * the launcher moving the windows itself. While one plays, the main pane's transform rides on the
 * transition root (TaskSurfaceCompat.liftIntoTransitionRoot) until the handler reports it finished.
 */
internal object LauncherTransitionRoot {
    fun install(module: XposedModule, loader: ClassLoader) {
        val methods = loader.loadClass("com.android.wm.shell.recents.RecentsTransitionHandler").declaredMethods.filter { method ->
            method.name == "startAnimation" && method.parameterTypes.any { it.name == TRANSITION_INFO } &&
                method.parameterTypes.any { it.isInterface && it.name.endsWith("TransitionFinishCallback") }
        }
        check(methods.isNotEmpty()) { "No RecentsTransitionHandler.startAnimation" }
        methods.forEach { method ->
            method.isAccessible = true
            val types = method.parameterTypes
            val infoAt = types.indexOfFirst { it.name == TRANSITION_INFO }
            val startAt = types.indexOfFirst { it == SurfaceControl.Transaction::class.java }
            val callbackAt = types.indexOfFirst { it.isInterface && it.name.endsWith("TransitionFinishCallback") }
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val args = chain.args.toTypedArray()
                    val transition = args.firstOrNull() ?: return chain.proceed()
                    val start = args.getOrNull(startAt) as? SurfaceControl.Transaction ?: return chain.proceed()
                    val info = args.getOrNull(infoAt) ?: return chain.proceed()
                    val callback = args.getOrNull(callbackAt) ?: return chain.proceed()
                    if (!TaskSurfaceCompat.liftIntoTransitionRoot(transition, info, start)) return chain.proceed()
                    // The lift ends before Shell commits the finish, whose writes put the pane back on the tasks.
                    args[callbackAt] = Proxy.newProxyInstance(loader, arrayOf(types[callbackAt])) { _, called, values ->
                        if (called.declaringClass != Any::class.java) TaskSurfaceCompat.dropTransitionRoot(transition)
                        called.invoke(callback, *(values ?: emptyArray()))
                    }
                    val played = try { chain.proceed(args) } catch (error: Throwable) {
                        TaskSurfaceCompat.dropTransitionRoot(transition, start)
                        throw error
                    }
                    if (played != true) TaskSurfaceCompat.dropTransitionRoot(transition, start)
                    return played
                }
            })
        }
        NeXtepLog.info("launcher_transition_root", "Installed recents transition lift methods=${methods.size}")
    }

    private const val TRANSITION_INFO = "android.window.TransitionInfo"
}
