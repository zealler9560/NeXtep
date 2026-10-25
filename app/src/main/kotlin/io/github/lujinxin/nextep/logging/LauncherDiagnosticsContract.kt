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
        val handler = Handler(Looper.getMainLooper())
        var complete = false
        fun finish(events: String?) {
            if (complete) return
            complete = true
            callback(events)
        }
        val home = TriggerBroadcastContract.resolveHomePackage(context) ?: return finish(null)
        val identity = PendingIntent.getBroadcast(
            context, RESULT_EVENTS,
            Intent(ACTION_QUERY).setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        handler.postDelayed({ finish(null) }, 800L)
        runCatching {
            context.sendOrderedBroadcast(
                Intent(ACTION_QUERY).setPackage(home).putExtra(EXTRA_IDENTITY, identity), null,
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
        receiver.resultCode = RESULT_EVENTS
        receiver.setResultExtras(android.os.Bundle().apply {
            putString(EXTRA_EVENTS, NeXtepLog.diagnostics().lines().takeLast(MAX_LINES).joinToString("\n"))
        })
    }
}
