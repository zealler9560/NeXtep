package io.github.lujinxin.nextep.config

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.trigger.TriggerCoordinator
import io.github.lujinxin.nextep.trigger.TriggerSource

/**
 * A launcher-visible switch that only toggles the workspace and leaves.
 * Gesture tools such as MyGesture can only "open an app", so this gives them
 * something to open. It makes the same in-process coordinator call as the QS
 * tile: an earlier version relayed the request through the broker receiver,
 * and on HyperOS that hop silently went nowhere (taps landed, nothing moved).
 * The activity stays alive until the coordinator answers, so its process
 * cannot be reaped mid-chain; a timeout keeps it from ever lingering.
 */
class ToggleWorkspaceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler.postDelayed(timeout, TIMEOUT_MS)
        TriggerCoordinator.requestToggle(this, TriggerSource.DESKTOP_SHORTCUT) { result ->
            runCatching {
                if (result is TriggerCoordinator.RequestResult.NotReady) {
                    Toast.makeText(this, R.string.tile_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
