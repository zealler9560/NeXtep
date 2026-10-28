package io.github.lujinxin.nextep.config

import android.app.Activity
import android.os.Bundle
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/**
 * A launcher-visible switch that only toggles the workspace and leaves at once.
 * Gesture tools such as MyGesture can only "open an app", so this trampolines
 * their open into the broker's host toggle — the same request the status-bar
 * gesture sends. It has to live in the module's own process: the broker is
 * guarded by a system permission, and only a same-UID sender gets through.
 */
class ToggleWorkspaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(TriggerBroadcastContract.hostToggleIntent())
        finish()
    }
}
