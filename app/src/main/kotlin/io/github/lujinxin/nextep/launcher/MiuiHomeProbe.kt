package io.github.lujinxin.nextep.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.LauncherDiagnosticsContract
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.xposed.HookGuard
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HyperOS desktop, reporting only: which activities the launcher resumes and what transform their
 * decor carries (the companion module scales it), answered to SystemUI's diagnostics export.
 */
object MiuiHomeProbe {
    private val registered = AtomicBoolean(false)
    private val reports = AtomicInteger(0)

    fun install(module: XposedModule) {
        HookGuard.run("miui_home_probe") {
            val onPostResume = Activity::class.java.getDeclaredMethod("onPostResume").apply { isAccessible = true }
            module.hook(onPostResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    (chain.thisObject as? Activity)?.let { activity -> HookGuard.run("miui_home_resumed") { resumed(activity) } }
                    return result
                }
            })
            NeXtepLog.info("miui_home", "Probe installed")
        }
    }

    private fun resumed(activity: Activity) {
        register(activity.applicationContext)
        // shortcut: the first 60 resumes only, enough for one export; a counter reset is not worth it.
        if (reports.incrementAndGet() > 60) return
        val decor = activity.window?.decorView
        val active = runCatching {
            android.provider.Settings.Global.getInt(activity.contentResolver, "nextep_workspace_active_v1", 0)
        }.getOrDefault(-1)
        NeXtepLog.info("miui_home", "Resumed ${activity.javaClass.name} workbench=$active decor=${decor?.width}x${decor?.height} " +
            "scale=${decor?.scaleX} tx=${decor?.translationX} ty=${decor?.translationY} pivot=${decor?.pivotX},${decor?.pivotY}")
    }

    private fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent) = LauncherDiagnosticsContract.answer(this, intent)
            }, IntentFilter(LauncherDiagnosticsContract.ACTION_QUERY), null, Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
            NeXtepLog.info("miui_home", "Diagnostics receiver ready")
        }.onFailure {
            registered.set(false)
            NeXtepLog.error("miui_home", "Diagnostics receiver failed", it)
        }
    }
}
