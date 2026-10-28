package io.github.lujinxin.nextep.systemui

import android.app.ActivityManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat

/** Observe native transitions and let workspace operations wait for their committed finish. */
internal object ShellTransitionLog {
    @Volatile private var idleRunner: ((Runnable) -> Unit)? = null
    private var attachedTransitions: Any? = null

    internal fun attachTransitions(transitions: Any) {
        if (attachedTransitions === transitions) return
        val executor = transitions.javaClass.getMethod("getMainExecutor").invoke(transitions)
            as java.util.concurrent.Executor
        val idle = transitions.javaClass.getMethod("runOnIdle", Runnable::class.java)
        idleRunner = { callback ->
            executor.execute {
                runCatching { idle.invoke(transitions, callback) }.onFailure {
                    NeXtepLog.warn("shell_transition", "Native idle callback unavailable; continuing launch", it)
                    callback.run()
                }
            }
        }
        attachedTransitions = transitions
    }

    /** Enqueue on Shell's thread, then return to SystemUI's main thread after native cleanup. */
    fun runOnIdle(action: () -> Unit): Boolean {
        val runner = idleRunner ?: return false
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        return runCatching { runner(Runnable { main.post(action) }); true }.getOrElse {
            NeXtepLog.warn("shell_transition", "Unable to enqueue native idle callback", it)
            false
        }
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        val transitions = loader.loadClass("com.android.wm.shell.transition.Transitions")
        val play = transitions.declaredMethods.single { it.name == "playTransition" && it.parameterCount == 1 }
        module.hook(play).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                runCatching { chain.thisObject?.let(::attachTransitions) }
                    .onFailure { NeXtepLog.warn("shell_transition", "Native idle bridge unavailable", it) }
                runCatching {
                    val active = chain.args.firstOrNull() ?: return@runCatching
                    val token = field(active, "mToken") ?: return@runCatching
                    val info = field(active, "mInfo") ?: return@runCatching
                    val tasks = (call(info, "getChanges") as? List<*>).orEmpty().mapNotNull { change ->
                        change?.let { call(it, "getTaskInfo") as? ActivityManager.RunningTaskInfo }?.taskId
                    }.toSet()
                    TaskSurfaceCompat.observeTransition(token, tasks)
                }.onFailure { NeXtepLog.warn("task_surface", "Native transition tracking unavailable", it) }
                return chain.proceed()
            }
        })
        val finish = transitions.declaredMethods.single {
            it.name == "onFinish" && it.parameterCount == 2 && it.parameterTypes[0] == android.os.IBinder::class.java
        }
        module.hook(finish).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching { chain.args.firstOrNull()?.let(TaskSurfaceCompat::finishTransition) }
                    .onFailure { NeXtepLog.warn("task_surface", "Native transition restore unavailable", it) }
                return result
            }
        })
        val methods = transitions.declaredMethods
            .filter { method -> method.name == "dispatchTransition" && method.parameterTypes.any { it.name == TRANSITION_INFO } }
        check(methods.isNotEmpty()) { "No Transitions.dispatchTransition" }
        methods.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    io.github.lujinxin.nextep.framework.TaskSurfaceCompat.markShellBusy()
                    val handler = chain.proceed()
                    if (SystemUiRuntime.isWorkspaceActive()) runCatching {
                        log(chain.args.firstOrNull { it?.javaClass?.name == TRANSITION_INFO }, handler)
                    }
                    return handler
                }
            })
        }
        installKeyguardGoingAway(module, loader)
        NeXtepLog.info("shell_transition", "Installed transition log methods=${methods.size}")
    }

    /** The keyguard-going-away request reaches Shell at unlock time, before WM collects the transition. */
    private fun installKeyguardGoingAway(module: XposedModule, loader: ClassLoader) {
        val method = loader.loadClass("com.android.wm.shell.transition.Transitions").declaredMethods
            .firstOrNull { it.name == "requestStartTransition" && it.parameterTypes.any { type -> type.name == TRANSITION_REQUEST } }
            ?: return NeXtepLog.warn("shell_transition", "No Transitions.requestStartTransition; unlock resumes on keyguard hidden")
        method.isAccessible = true
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                runCatching {
                    val request = chain.args.firstOrNull { it?.javaClass?.name == TRANSITION_REQUEST } ?: return@runCatching
                    if (isKeyguardGoingAway(call(request, "getFlags") as? Int ?: 0)) SystemUiRuntime.onKeyguardGoingAway()
                }
                return chain.proceed()
            }
        })
    }

    fun isKeyguardGoingAway(flags: Int): Boolean = flags and TRANSIT_FLAG_KEYGUARD_GOING_AWAY != 0

    /** [how]: "handler" for one Shell picked by asking each, "preset" for one that claimed the request. */
    internal fun log(info: Any?, handler: Any?, how: String = "handler") {
        info ?: return
        val changes = (call(info, "getChanges") as? List<*>).orEmpty().joinToString(",") { change ->
            if (change == null) return@joinToString "?"
            val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo
            "${call(change, "getMode")}/${task?.taskId ?: "-"}/${call(change, "getStartDisplayId")}>${call(change, "getEndDisplayId")}"
        }
        NeXtepLog.info("shell_transition", "type=${call(info, "getType")} $how=${handler?.javaClass?.name?.substringAfterLast('.')} " +
            "changes=[$changes]")
    }

    private fun call(target: Any, name: String): Any? = runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()

    private fun field(target: Any, name: String): Any? = generateSequence<Class<*>>(target.javaClass) { it.superclass }
        .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } }
        ?.apply { isAccessible = true }?.get(target)

    private const val TRANSITION_INFO = "android.window.TransitionInfo"
    private const val TRANSITION_REQUEST = "android.window.TransitionRequestInfo"
    /** WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY (hidden). */
    private const val TRANSIT_FLAG_KEYGUARD_GOING_AWAY = 1 shl 8
}
