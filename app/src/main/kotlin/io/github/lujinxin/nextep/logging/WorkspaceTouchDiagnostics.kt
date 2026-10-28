package io.github.lujinxin.nextep.logging

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import io.github.lujinxin.nextep.BuildConfig
import io.github.lujinxin.nextep.framework.TaskSurfaceCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Test-build-only observation; never transforms, injects or claims an input event. */
internal object WorkspaceTouchDiagnostics {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "Panes-TouchDiagnostics").apply { isDaemon = true } }
    private val pending = AtomicBoolean()
    private val records = java.util.ArrayDeque<String>()
    private var until = 0L
    private var visible = false
    private var lastSample = -2_000L
    private var moves = 0
    private var stream = -1L
    private var sampled = false
    private var firstX = 0f
    private var firstY = 0f

    fun workspace(active: Boolean) {
        if (!BuildConfig.TOUCH_DIAGNOSTICS) return
        if (active && !visible) {
            until = SystemClock.uptimeMillis() + 90_000L
            add("session start=${SystemClock.uptimeMillis()} duration=90s build=${BuildConfig.DIAGNOSTIC_BUILD}")
        }
        visible = active
        if (!active) { stream = -1L; add("session workspace hidden") }
    }

    fun observe(context: Context, event: MotionEvent, unlocked: Boolean) {
        if (!BuildConfig.TOUCH_DIAGNOSTICS || !unlocked || !visible || SystemClock.uptimeMillis() > until) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stream = event.downTime
            moves = 0
            sampled = false
            firstX = event.rawX
            firstY = event.rawY
            add("down t=$stream monitorDisplay=0 raw=$firstX,$firstY pointers=${event.pointerCount} " +
                TaskSurfaceCompat.touchDiagnostics(firstX, firstY))
            if (firstY <= context.resources.displayMetrics.density * 48 && event.eventTime - lastSample >= 2_000) {
                add("bars at down=$stream\n" + io.github.lujinxin.nextep.systemui.WorkspaceStatusBarCompat.diagnostics(details = true))
            }
        }
        if (stream != event.downTime) return
        if (event.actionMasked == MotionEvent.ACTION_MOVE) moves++
        // Sample after dispatch has begun, while a held tap/swipe is still down. No dump per MOVE.
        if (!sampled && event.actionMasked in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE) &&
            event.eventTime - lastSample >= 2_000 && pending.compareAndSet(false, true)) {
            sampled = true
            lastSample = event.eventTime
            val at = event.eventTime
            val down = stream
            worker.schedule({
                try {
                    val process = ProcessBuilder("/system/bin/dumpsys", "-t", "1", "input").redirectErrorStream(true).start()
                    val dump = try {
                        process.inputStream.reader().use { reader ->
                            val text = StringBuilder()
                            val buffer = CharArray(4096)
                            while (text.length < 512_000) {
                                val read = reader.read(buffer)
                                if (read < 0) break
                                text.append(buffer, 0, read)
                            }
                            text.toString()
                        }
                    } finally { process.destroy() }
                    val versions = InputDispatchSnapshot.packages(dump).joinToString("\n") { pkg ->
                        runCatching {
                            val info = context.packageManager.getPackageInfo(pkg, 0)
                            "$pkg version=${info.versionName?.replace(Regex("[^a-zA-Z0-9_.+-]"), "_")?.take(80)} code=${info.longVersionCode}"
                        }.getOrDefault("$pkg version=unavailable")
                    }
                    add("dispatch down=$down requested=$at captured=${SystemClock.uptimeMillis()}\n$versions\n" + InputDispatchSnapshot.sanitize(dump))
                } catch (error: Exception) {
                    add("dispatch down=$down unavailable=${error.javaClass.simpleName}")
                } finally { pending.set(false) }
            }, 80, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            add("end down=$stream t=${event.eventTime} action=${event.actionMasked} raw=${event.rawX},${event.rawY} " +
                "delta=${event.rawX - firstX},${event.rawY - firstY} moves=$moves sampled=$sampled")
            stream = -1L
        }
    }

    private fun add(value: String) = synchronized(records) {
        while (records.size >= 60) records.removeFirst()
        records.addLast(value)
        while (records.sumOf { it.length } > 160_000 && records.size > 1) records.removeFirst()
    }

    fun export(): String = synchronized(records) {
        "enabled=${BuildConfig.TOUCH_DIAGNOSTICS} loadedBuild=${BuildConfig.DIAGNOSTIC_BUILD} pending=${pending.get()} " +
            "visible=$visible remainingMs=${(until - SystemClock.uptimeMillis()).coerceAtLeast(0)}\n" +
            records.joinToString("\n")
    }
}
