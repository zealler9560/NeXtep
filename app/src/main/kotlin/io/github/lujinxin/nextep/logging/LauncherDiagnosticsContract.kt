package io.github.lujinxin.nextep.logging

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * The launcher's recent module events for the diagnostics export: desktop picks, redirects and the
 * desktop transform run in the launcher process, whose log SystemUI cannot otherwise read.
 */
object LauncherDiagnosticsContract {
    const val ACTION_QUERY = "io.github.yishisanren.panes.action.QUERY_LAUNCHER_DIAGNOSTICS"
    const val EXTRA_IDENTITY = "systemui_identity"
    const val EXTRA_EVENTS = "events"
    const val RESULT_EVENTS = 35_201
    const val MAX_LINES = 400

    /** [callback] gets the events, or null when the launcher does not answer within 800 ms. */
    fun query(context: Context, callback: (String?) -> Unit) {
        val home = TriggerBroadcastContract.resolveHomePackage(context) ?: return callback(null)
        queryPackage(context, home, callback)
    }

    /** Keep companion ownership probing separate from the assistant's export-only reply. */
    fun queryForExport(context: Context, callback: (String?) -> Unit) {
        query(context) { home ->
            if (!io.github.lujinxin.nextep.BuildConfig.TOUCH_DIAGNOSTICS) callback(home)
            else queryPackage(context, TriggerBroadcastContract.ASSISTANT_SCREEN_PACKAGE) { assistant ->
                callback(listOfNotNull(home, "--- assistant process\n${assistant ?: "no diagnostic responder"}").joinToString("\n"))
            }
        }
    }

    private fun queryPackage(context: Context, target: String, callback: (String?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var complete = false
        fun finish(events: String?) {
            if (complete) return
            complete = true
            callback(events)
        }
        val identity = PendingIntent.getBroadcast(
            context, RESULT_EVENTS,
            Intent(ACTION_QUERY).setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        handler.postDelayed({ finish(null) }, 800L)
        runCatching {
            context.sendOrderedBroadcast(
                Intent(ACTION_QUERY).setPackage(target).putExtra(EXTRA_IDENTITY, identity), null,
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        finish(if (resultCode == RESULT_EVENTS) getResultExtras(false)?.getString(EXTRA_EVENTS) else null)
                    }
                },
                handler, 0, null, null,
            )
        }.onFailure { finish(null) }
    }

    /** Launcher side: answers only SystemUI's ordered query. */
    fun answer(receiver: BroadcastReceiver, intent: Intent) {
        if (!receiver.isOrderedBroadcast) return
        val identity = intent.getParcelableExtra(EXTRA_IDENTITY, PendingIntent::class.java)
        if (identity?.creatorPackage != TriggerBroadcastContract.SYSTEM_UI_PACKAGE) return
        // On HyperOS the companion module answers in the same process; keep what an earlier responder wrote.
        val earlier = receiver.getResultExtras(false)?.getString(EXTRA_EVENTS)?.takeIf { receiver.resultCode == RESULT_EVENTS }
        val own = NeXtepLog.diagnostics().lines().takeLast(MAX_LINES).joinToString("\n")
        val roots = if (io.github.lujinxin.nextep.BuildConfig.TOUCH_DIAGNOSTICS)
            io.github.lujinxin.nextep.launcher.LauncherTransformController.diagnostics() else null
        receiver.resultCode = RESULT_EVENTS
        receiver.setResultExtras(android.os.Bundle().apply {
            putString(EXTRA_EVENTS, listOfNotNull(earlier, own, roots).joinToString("\n--- next responder\n"))
        })
    }
}
