package io.github.lujinxin.nextep.systemui

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** Finish workspace exchanges through Shell's existing no-animation path. */
internal object WorkspaceDisplayTransitionHook {
    @Volatile private var displayManager: DisplayManager? = null
    @Volatile private var homePackage: String? = null

    fun initialize(context: Context) {
        displayManager = context.getSystemService(DisplayManager::class.java)
        homePackage = TriggerBroadcastContract.resolveHomePackage(context)
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        val handlerClass = loader.loadClass("com.android.wm.shell.transition.DefaultTransitionHandler")
        // Verified against the captured ColorOS implementation: this check precedes
        // background/snapshot animation creation. Its native fast path applies startT
        // and invokes the finish callback itself. Omitting only loadAnimation leaves
        // background animation active and does not complete the exchange immediately.
        val method = handlerClass
            .declaredMethods.singleOrNull {
                it.name == "isAnimationsDisabledForAnyDisplay" &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.singleOrNull()?.name == "android.window.TransitionInfo"
            }?.apply { isAccessible = true } ?: return installImmediateFinish(module, loader)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val info = chain.args.firstOrNull()
                val exchange = runCatching {
                    info != null && SystemUiRuntime.isWorkspaceActive() && isWorkspaceExchange(info)
                }.getOrDefault(false)
                if (!exchange) return chain.proceed()
                NeXtepLog.info("workspace_display_transition", "Using native immediate finish for workspace exchange")
                return true
            }
        })
        NeXtepLog.info("workspace_display_transition", "Installed scoped native exchange completion")
    }

    /**
     * HyperOS has no such check, and its own handlers claim a workspace exchange when it is requested,
     * so it never reaches the default handler. Finish it the same way before any handler plays it:
     * commit its start, then report it finished, as that fast path does.
     */
    private fun installImmediateFinish(module: XposedModule, loader: ClassLoader) {
        val handlerClass = loader.loadClass("com.android.wm.shell.transition.DefaultTransitionHandler")
        val transitions = loader.loadClass("com.android.wm.shell.transition.Transitions")
        val play = transitions.declaredMethods.single { it.name == "playTransition" && it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
        val finish = transitions.declaredMethods.single {
            it.name == "onFinish" && it.parameterTypes.size == 2 && it.parameterTypes[0] == android.os.IBinder::class.java
        }.apply { isAccessible = true }
        module.hook(play).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val active = chain.args.firstOrNull() ?: return chain.proceed()
                if (!SystemUiRuntime.isWorkspaceActive()) return chain.proceed()
                val info = field(active, "mInfo") ?: return chain.proceed()
                val preset = field(active, "mHandler")
                // Shell's dispatch log never sees a claimed transition.
                if (preset != null) ShellTransitionLog.log(info, preset, "preset")
                if (!runCatching { isWorkspaceExchange(info) }.getOrDefault(false)) return chain.proceed()
                val token = field(active, "mToken") ?: return chain.proceed()
                // Shell's finish bookkeeping names the handler that played it: the default one, as on ColorOS.
                val default = (field(chain.thisObject!!, "mHandlers") as? List<*>)?.firstOrNull { handlerClass.isInstance(it) }
                    ?: return chain.proceed()
                (field(active, "mStartT") as android.view.SurfaceControl.Transaction).apply()
                runCatching {
                    preset?.javaClass?.methods?.firstOrNull { it.name == "onTransitionConsumed" && it.parameterTypes.size == 3 }
                        ?.invoke(preset, token, false, null)
                }
                setField(active, "mHandler", default)
                return try {
                    finish.invoke(chain.thisObject, token, null)
                    NeXtepLog.info("workspace_display_transition", "Finished workspace exchange at once instead of ${
                        preset?.javaClass?.name?.substringAfterLast('.') ?: "dispatch"}")
                    null
                } catch (error: Throwable) {
                    NeXtepLog.error("workspace_display_transition", "Immediate exchange finish failed; Shell plays it", error)
                    setField(active, "mHandler", preset)
                    chain.proceed()
                }
            }
        })
        NeXtepLog.info("workspace_display_transition", "Installed immediate exchange completion at play")
    }

    private fun declared(target: Any, name: String): java.lang.reflect.Field? = generateSequence<Class<*>>(target.javaClass) { it.superclass }
        .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } }?.apply { isAccessible = true }

    private fun field(target: Any, name: String): Any? = declared(target, name)?.get(target)

    private fun setField(target: Any, name: String, value: Any?) { declared(target, name)?.set(target, value) }

    private fun isWorkspaceExchange(info: Any): Boolean {
        val type = call(info, "getType") as? Int ?: return false
        if (call(info, "isKeyguardGoingAway") == true) return false
        val changes = call(info, "getChanges") as? List<*> ?: return false
        val summaries = changes.map { change ->
            change ?: return false
            val mode = call(change, "getMode") as? Int ?: return false
            val task = call(change, "getTaskInfo") as? ActivityManager.RunningTaskInfo
            val rotates = call(change, "getStartRotation") != call(change, "getEndRotation")
            val kind = if (task != null) {
                val state = TaskInfoCompat.readWindowState(task)
                if (state == null || state.vendorWindowed || state.windowingMode != 1) {
                    WorkspaceDisplayExchangePolicy.Kind.OTHER
                } else if (WorkspaceDisplayExchangePolicy.isExchangeMode(mode) &&
                    isExchanging(change, task.taskId)) {
                    WorkspaceDisplayExchangePolicy.Kind.EXCHANGE
                } else if (state.displayId == Display.DEFAULT_DISPLAY && homePackage != null &&
                    (task.topActivity ?: task.baseActivity)?.packageName == homePackage
                ) {
                    WorkspaceDisplayExchangePolicy.Kind.HOME
                } else WorkspaceDisplayExchangePolicy.Kind.OTHER
            } else {
                val flags = call(change, "getFlags") as? Int ?: return false
                fun stationary() = when (val displayId = call(change, "getEndDisplayId") as? Int) {
                    Display.DEFAULT_DISPLAY -> WorkspaceDisplayExchangePolicy.Kind.STATIONARY_DISPLAY
                    null -> WorkspaceDisplayExchangePolicy.Kind.OTHER
                    else -> if (isSlotDisplay(displayId)) WorkspaceDisplayExchangePolicy.Kind.STATIONARY_SLOT_DISPLAY
                        else WorkspaceDisplayExchangePolicy.Kind.OTHER
                }
                when {
                    flags and FLAG_IS_WALLPAPER != 0 -> WorkspaceDisplayExchangePolicy.Kind.WALLPAPER
                    flags and FLAG_IS_DISPLAY != 0 && mode == TRANSIT_CHANGE &&
                        call(change, "getStartDisplayId") == call(change, "getEndDisplayId") &&
                        call(change, "getStartAbsBounds") == call(change, "getEndAbsBounds") -> stationary()
                    // HyperOS lists the display area a task arrives in, reordered to the front, beside it.
                    flags and FLAG_IS_TASK_DISPLAY_AREA != 0 &&
                        call(change, "getStartDisplayId") == call(change, "getEndDisplayId") -> stationary()
                    else -> WorkspaceDisplayExchangePolicy.Kind.OTHER
                }
            }
            WorkspaceDisplayExchangePolicy.Change(kind, rotates)
        }
        val movesIntoSlot = changes.any { change ->
            val end = call(change!!, "getEndDisplayId") as? Int
            call(change, "getTaskInfo") != null && end != null && end != call(change, "getStartDisplayId") && isSlotDisplay(end)
        }
        val exchange = WorkspaceDisplayExchangePolicy.shouldFinishImmediately(
            type, summaries, hasRecentSlotMove = TaskSurfaceCompat.hasRecentSlotMove(), movesIntoSlot = movesIntoSlot,
        )
        if (!exchange && summaries.any { it.kind == WorkspaceDisplayExchangePolicy.Kind.EXCHANGE }) {
            NeXtepLog.info("workspace_display_transition", "Exchange type=$type plays natively: " +
                changes.zip(summaries).joinToString(",", "[", "]") { (change, summary) ->
                    "${call(change!!, "getMode")}/0x${Integer.toHexString(call(change, "getFlags") as Int)}/${summary.kind}" +
                        if (summary.rotates) "/rotates" else ""
                })
        }
        if (exchange) changes.forEach { change ->
            (change?.let { call(it, "getTaskInfo") } as? ActivityManager.RunningTaskInfo)
                ?.let { TaskSurfaceCompat.observeTaskInfo(it) }
        }
        return exchange
    }

    private fun isExchanging(change: Any, taskId: Int): Boolean {
        if (TaskSurfaceCompat.isDisplayExchanging(taskId)) return true
        val start = call(change, "getStartDisplayId") as? Int ?: return false
        val end = call(change, "getEndDisplayId") as? Int ?: return false
        if (start == end || (start != Display.DEFAULT_DISPLAY && end != Display.DEFAULT_DISPLAY)) return false
        return isSlotDisplay(if (start == Display.DEFAULT_DISPLAY) end else start)
    }

    private fun isSlotDisplay(displayId: Int): Boolean =
        displayManager?.getDisplay(displayId)?.name?.startsWith("NeXtep-slot-") == true

    private fun call(target: Any, name: String): Any? = target.javaClass.getMethod(name).invoke(target)

    private const val TRANSIT_CHANGE = 6
    private const val FLAG_IS_WALLPAPER = 2
    private const val FLAG_IS_DISPLAY = 32
    /** TransitionInfo.FLAG_IS_TASK_DISPLAY_AREA (hidden). */
    private const val FLAG_IS_TASK_DISPLAY_AREA = 1 shl 23
}
