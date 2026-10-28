package io.github.lujinxin.nextep.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lujinxin.nextep.logging.NeXtepLog

class WorkspaceControlBrokerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Read before goAsync(), which takes the pending result this reads from.
        val ordered = isOrderedBroadcast
        val pendingResult = goAsync()
        val callback: (TriggerCoordinator.RequestResult) -> Unit = { result ->
            NeXtepLog.info("trigger_broker", "SystemUI request completed result=$result")
            pendingResult.finish()
        }
        when (intent.action) {
            TriggerBroadcastContract.ACTION_HOST_TOGGLE_REQUEST ->
                TriggerCoordinator.requestToggle(
                    context,
                    TriggerSource.TOP_RIGHT_STATUS_BAR_GESTURE,
                    callback,
                )
            // SystemUI sends this ordered. The retired HyperOS companion module relays every SystemUI set it
            // sees as a plain broadcast, and acting on it made SystemUI set again: the workbench opened and
            // closed every few hundred milliseconds (HyperOS 3).
            TriggerBroadcastContract.ACTION_HOST_SET_REQUEST -> if (!ordered) {
                NeXtepLog.info("trigger_broker", "Ignored a relayed set request")
                pendingResult.finish()
            } else
                TriggerCoordinator.requestSet(
                    context,
                    intent.getBooleanExtra(
                        TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE,
                        false,
                    ),
                    TriggerSource.SYSTEM_RECOVERY,
                    callback,
                )
            TriggerBroadcastContract.ACTION_HOST_SCENE_REQUEST -> {
                val name = intent.getStringExtra(TriggerBroadcastContract.EXTRA_SCENE)?.takeIf { it.isNotBlank() }
                if (name == null) pendingResult.finish()
                else TriggerCoordinator.restoreScene(context, name) { result ->
                    if (result !is TriggerCoordinator.RequestResult.Ready) {
                        android.widget.Toast.makeText(context, io.github.lujinxin.nextep.R.string.scene_shortcut_failed,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    callback(result)
                }
            }
            else -> pendingResult.finish()
        }
    }
}
