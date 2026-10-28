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
        HookGuard.run("miui_home_scale_probe") { installScaleProbe(module) }
        // Reporting must not wait for a resumed activity: no HyperOS export has seen one yet.
        android.os.Handler(Looper.getMainLooper()).post { registerFromApplication(attempt = 1) }
    }

    private fun registerFromApplication(attempt: Int) {
        val application = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.app.Application
        }.getOrNull()
        if (application != null) {
            NeXtepLog.info("miui_home", "Loaded in ${application.packageName} pid=${android.os.Process.myPid()} attempt=$attempt")
            return register(application)
        }
        if (attempt < 20) android.os.Handler(Looper.getMainLooper()).postDelayed({ registerFromApplication(attempt + 1) }, 500L)
    }

    private val scaleSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var activeReadAt = 0L
    @Volatile private var active = false

    /**
     * Which launcher code scales which view while the workbench shows: the desktop's own zoom on
     * returning home and around the minus-one screen is drawn by the launcher, and the frames to
     * turn it off are named only here. One line per view class and caller every two seconds.
     */
    private fun installScaleProbe(module: XposedModule) {
        val setters = listOf(android.view.View::class.java.getDeclaredMethod("setScaleX", Float::class.javaPrimitiveType),
            android.view.View::class.java.getDeclaredMethod("setScaleY", Float::class.javaPrimitiveType),
            android.view.ViewPropertyAnimator::class.java.getDeclaredMethod("scaleX", Float::class.javaPrimitiveType),
            android.view.ViewPropertyAnimator::class.java.getDeclaredMethod("scaleY", Float::class.javaPrimitiveType))
        setters.forEach { method ->
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    runCatching { scaled(chain.thisObject, method.name, chain.args.firstOrNull() as? Float) }
                    return chain.proceed()
                }
            })
        }
        NeXtepLog.info("miui_home", "Scale probe installed")
    }

    private fun scaled(target: Any?, setter: String, value: Float?) {
        val view = (target as? android.view.View) ?: (target as? android.view.ViewPropertyAnimator)?.let { animator ->
            runCatching { animator.javaClass.getDeclaredField("mView").apply { isAccessible = true }.get(animator) as? android.view.View }.getOrNull()
        } ?: return
        // Launcher views only, and only full-size ones: an icon's press scale is noise.
        if (view.width < 600 || view.height < 600) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - activeReadAt > 500) {
            activeReadAt = now
            active = runCatching {
                android.provider.Settings.Global.getInt(view.context.contentResolver, "nextep_workspace_active_v2", 0) == 1
            }.getOrDefault(false)
        }
        if (!active) return
        val caller = Thread.currentThread().stackTrace.firstOrNull { frame ->
            frame.className.startsWith("com.miui") && !frame.className.contains("Probe")
        }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "?"
        val key = "${view.javaClass.name}/$caller"
        val last = scaleSeen[key] ?: 0L
        if (now - last < 2_000) return
        scaleSeen[key] = now
        NeXtepLog.info("miui_home", "Scaled ${view.javaClass.name} ${view.width}x${view.height} $setter=$value by $caller")
    }

    private fun resumed(activity: Activity) {
        register(activity.applicationContext)
        // shortcut: the first 60 resumes only, enough for one export; a counter reset is not worth it.
        if (reports.incrementAndGet() > 60) return
        val decor = activity.window?.decorView
        val active = runCatching {
            android.provider.Settings.Global.getInt(activity.contentResolver, "nextep_workspace_active_v2", 0)
        }.getOrDefault(-1)
        // The HyperOS launcher ships a Flutter engine (libapp.so); Dart-drawn views carry no scale a View hook could see.
        val flutter = decor?.let { count(it) { view -> view.javaClass.name.contains("Flutter") } } ?: 0
        NeXtepLog.info("miui_home", "Resumed ${activity.javaClass.name} workbench=$active decor=${decor?.width}x${decor?.height} " +
            "scale=${decor?.scaleX} tx=${decor?.translationX} ty=${decor?.translationY} pivot=${decor?.pivotX},${decor?.pivotY} flutterViews=$flutter")
    }

    private fun count(view: android.view.View, matches: (android.view.View) -> Boolean): Int =
        (if (matches(view)) 1 else 0) + ((view as? android.view.ViewGroup)?.let { group ->
            (0 until group.childCount).sumOf { count(group.getChildAt(it), matches) } } ?: 0)

    private fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        // The ordered query went unanswered on every HyperOS export so far; the setting tells whether this runs at all.
        NeXtepLog.mirror(context, NeXtepLog.LAUNCHER_EVENTS_KEY)
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
