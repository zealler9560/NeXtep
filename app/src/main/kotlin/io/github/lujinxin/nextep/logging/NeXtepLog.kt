package io.github.lujinxin.nextep.logging

import android.os.Build
import android.util.Log

object NeXtepLog {
    private const val TAG = "NeXtep"
    private const val MAX_EVENTS = 2_000
    // Messages carry ids, flags, geometry and component names. Anything URI-shaped (a link, an
    // intent with data) is masked in case one slips in; search phrases and dragged text are never logged.
    private val URI = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*:(//|#Intent)\S*""")
    private val events = java.util.ArrayDeque<String>()

    fun diagnostics(): String = synchronized(events) { events.joinToString("\n") }

    fun recent(count: Int): String = synchronized(events) { events.toList().takeLast(count).joinToString("\n") }

    /** Runs after each event: system_server mirrors its own into SystemUI's export through it. */
    @Volatile var onEvent: (() -> Unit)? = null

    internal fun masked(message: String): String = URI.replace(message, "<uri>")

    fun debug(feature: String, message: String) = write(Log.DEBUG, feature, message, null)
    fun info(feature: String, message: String) = write(Log.INFO, feature, message, null)
    fun warn(feature: String, message: String, error: Throwable? = null) =
        write(Log.WARN, feature, message, error)
    fun error(feature: String, message: String, error: Throwable? = null) =
        write(Log.ERROR, feature, message, error)

    private fun write(priority: Int, feature: String, message: String, error: Throwable?) {
        synchronized(events) {
            if (events.size == MAX_EVENTS) events.removeFirst()
            events.addLast("${android.os.SystemClock.uptimeMillis()} priority=$priority feature=$feature " +
                "error=${error?.javaClass?.simpleName ?: "none"} message=${masked(message)}" +
                (error?.let { " cause=${masked((it.message ?: it.cause?.toString()).toString())}" } ?: ""))
        }
        val enriched = "pid=${android.os.Process.myPid()} thread=${Thread.currentThread().name} " +
            "feature=$feature sdk=${Build.VERSION.SDK_INT} message=$message"
        val output = if (error == null) enriched else "$enriched\n${Log.getStackTraceString(error)}"
        Log.println(priority, TAG, output)
        onEvent?.invoke()
    }
}
