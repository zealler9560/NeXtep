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

    @Volatile private var onEvent: (() -> Unit)? = null
    private val mirrorPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Another process's events reach SystemUI's diagnostics export only through a setting: the
     * last [count] are written there a second after the latest. Needs WRITE_SECURE_SETTINGS.
     */
    fun mirror(context: android.content.Context, key: String, count: Int = 80) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        onEvent = {
            if (mirrorPending.compareAndSet(false, true)) handler.postDelayed({
                mirrorPending.set(false)
                val text = recent(count)
                runCatching { android.provider.Settings.Global.putString(context.contentResolver, key, text) }.onFailure {
                    // Without the settings permission, hand them to SystemUI directly.
                    Log.w(TAG, "Event mirror $key failed: $it")
                    runCatching { context.sendBroadcast(android.content.Intent(ACTION_MIRROR).setPackage(SYSTEM_UI)
                        .putExtra(EXTRA_KEY, key).putExtra(EXTRA_TEXT, text.takeLast(MAX_MIRROR_CHARS))) }
                }
            }, 1_000L)
        }
        onEvent?.invoke()
    }

    fun mirrored(context: android.content.Context, key: String): String? =
        received[key] ?: runCatching { android.provider.Settings.Global.getString(context.contentResolver, key) }.getOrNull()

    private val received = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** SystemUI side of the broadcast fallback; only known keys, bounded, kept for the export. */
    fun receiveMirrors(context: android.content.Context) {
        context.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: android.content.Context?, intent: android.content.Intent) {
                val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { it == LAUNCHER_EVENTS_KEY || it == SYSTEM_SERVER_EVENTS_KEY } ?: return
                received[key] = intent.getStringExtra(EXTRA_TEXT).orEmpty().takeLast(MAX_MIRROR_CHARS)
            }
        }, android.content.IntentFilter(ACTION_MIRROR), android.content.Context.RECEIVER_EXPORTED)
    }

    private const val ACTION_MIRROR = "io.github.yishisanren.panes.action.MIRROR_EVENTS"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_TEXT = "text"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val MAX_MIRROR_CHARS = 32_000

    const val SYSTEM_SERVER_EVENTS_KEY = "nextep_system_server_events_v1"
    const val LAUNCHER_EVENTS_KEY = "nextep_launcher_events_v1"

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
