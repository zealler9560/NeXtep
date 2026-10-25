package io.github.lujinxin.nextep.display

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * While the workspace shows, logs what each pane's app costs every 30 s: CPU time since the
 * previous sample, process importance, whether its task is visible, the pane's display rate, and
 * any process exit in between. This is how a paused or rate-limited pane is checked on a phone.
 */
internal class SlotUsageProbe(context: Context) {
    data class Pane(val name: String, val taskId: Int, val paused: Boolean, val refreshRate: Float?)

    private val applicationContext = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "NeXtep-Usage") }
    private val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
    private val health = applicationContext.getSystemService(SystemHealthManager::class.java)
    private var panes: (() -> List<Pane>?)? = null
    // Worker thread only.
    private val lastCpu = HashMap<Int, Long>()
    private var lastSample = 0L
    private val reported = mutableSetOf<String>()

    private val tick = object : Runnable {
        override fun run() {
            panes?.invoke()?.let { current -> worker.execute { sample(current, report = true) } }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(panes: () -> List<Pane>?) {
        this.panes = panes
        handler.removeCallbacks(tick)
        // A silent first sample sets the baseline, so the first report covers 30 s of this session.
        handler.post { panes()?.let { current -> worker.execute { lastCpu.clear(); sample(current, report = false) } } }
        handler.postDelayed(tick, INTERVAL_MS)
    }

    fun stop() {
        panes = null
        handler.removeCallbacks(tick)
    }

    private fun sample(panes: List<Pane>, report: Boolean) {
        val now = System.currentTimeMillis()
        val tasks = attempt("tasks") { activityManager.getRunningTasks(MAX_TASKS) }.orEmpty()
        val processes = attempt("processes") { activityManager.runningAppProcesses }.orEmpty()
        val packages = mutableSetOf<String>()
        val parts = panes.map { pane ->
            val task = tasks.firstOrNull { it.taskId == pane.taskId }
            val packageName = (task?.topActivity ?: task?.baseActivity)?.packageName
            val uid = packageName?.let { packages += it; uidOf(it, task?.let(TaskInfoCompat::readUserId) ?: 0) }
            val cpu = uid?.let(::cpuMs)
            val delta = if (uid != null && cpu != null) lastCpu.put(uid, cpu)?.let { cpu - it } else null
            val importance = uid?.let { owner -> processes.filter { it.uid == owner }.minOfOrNull { it.importance } }
            "${pane.name}=$packageName ${if (pane.paused) "paused" else "live"} cpu=${delta?.let { "+${it}ms" } ?: "?"} " +
                "importance=${importance ?: "none"} visible=${task?.isVisible} fps=${pane.refreshRate}"
        }
        if (report) {
            NeXtepLog.info("slot_usage", "window=${(now - lastSample) / 1000}s " + parts.joinToString(" | "))
            packages.forEach { packageName ->
                attempt("exits") { activityManager.getHistoricalProcessExitReasons(packageName, 0, 3) }.orEmpty()
                    .filter { it.timestamp > lastSample }
                    .forEach {
                        NeXtepLog.info("slot_usage", "Exited package=$packageName reason=${it.reason} " +
                            "importance=${it.importance} pss=${it.pss}kB description=${it.description}")
                    }
            }
        }
        lastSample = now
    }

    private fun uidOf(packageName: String, userId: Int): Int? = attempt("uid") {
        applicationContext.packageManager.getApplicationInfo(packageName, 0).uid % PER_USER_RANGE + userId * PER_USER_RANGE
    }

    private fun cpuMs(uid: Int): Long? = attempt("cpu") {
        val stats = health.takeUidSnapshot(uid)
        listOf(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS, UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS)
            .sumOf { if (stats.hasMeasurement(it)) stats.getMeasurement(it) else 0L }
    }

    /** One failure line per kind: the report shows "?" where a reading was unavailable. */
    private fun <T> attempt(kind: String, read: () -> T): T? = runCatching(read).onFailure {
        if (reported.add(kind)) NeXtepLog.warn("slot_usage", "Unavailable $kind: ${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    private companion object {
        const val INTERVAL_MS = 30_000L
        const val MAX_TASKS = 64
        const val PER_USER_RANGE = 100_000
    }
}
