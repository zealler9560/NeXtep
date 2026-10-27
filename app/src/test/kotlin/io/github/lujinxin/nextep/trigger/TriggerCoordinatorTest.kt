package io.github.lujinxin.nextep.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TriggerCoordinatorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** The resolved HOME is cached for a minute across tests; each test names its own. */
    @After fun forgetHome() {
        TriggerBroadcastContract::class.java.getDeclaredField("homePackageCache").apply { isAccessible = true }.set(null, null)
    }

    /** Fake hosts: each replies active as configured and records every set request. */
    private fun hosts(systemUiActive: Boolean, launcherActive: Boolean, sets: MutableList<Pair<String, Boolean>>,
                      launcher: String = "test.launcher", launcherAnswers: Boolean = true) {
        forgetHome()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        shadowOf(context.packageManager).addResolveInfoForIntent(home, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = launcher; name = "$launcher.Home"
                applicationInfo = ApplicationInfo().apply { packageName = launcher }
            }
        })
        val filter = IntentFilter().apply {
            addAction(TriggerBroadcastContract.ACTION_QUERY_WORKSPACE)
            addAction(TriggerBroadcastContract.ACTION_SET_WORKSPACE)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val target = intent.`package`.orEmpty()
                if (target != TriggerBroadcastContract.SYSTEM_UI_PACKAGE && !launcherAnswers) return
                var active = if (target == TriggerBroadcastContract.SYSTEM_UI_PACKAGE) systemUiActive else launcherActive
                if (intent.action == TriggerBroadcastContract.ACTION_SET_WORKSPACE) {
                    active = intent.getBooleanExtra(TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE, false)
                    sets += target to active
                }
                resultCode = TriggerBroadcastContract.RESULT_HANDLED
                setResultExtras(Bundle().apply {
                    putBoolean(TriggerBroadcastContract.EXTRA_ACTIVE, active)
                    putString(TriggerBroadcastContract.EXTRA_HOME_PACKAGE, launcher)
                    putInt(TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION, TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION)
                })
            }
        }, filter, Context.RECEIVER_EXPORTED)
    }

    @Test fun tileRefreshNeverClosesAWorkspaceThatSystemUiReportsActive() {
        val sets = mutableListOf<Pair<String, Boolean>>()
        // Settings.Global still reads inactive: SystemUI is mid-entry and has not published yet.
        hosts(systemUiActive = true, launcherActive = true, sets)
        var result: TriggerCoordinator.RequestResult? = null
        TriggerCoordinator.queryState(context) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sets.isEmpty())
        assertTrue((result as TriggerCoordinator.RequestResult.Ready).active)
    }

    @Test fun onlyTheLauncherIsResetWhenSystemUiIsInactive() {
        val sets = mutableListOf<Pair<String, Boolean>>()
        hosts(systemUiActive = false, launcherActive = true, sets)
        var result: TriggerCoordinator.RequestResult? = null
        TriggerCoordinator.queryState(context) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("test.launcher" to false), sets)
        assertFalse((result as TriggerCoordinator.RequestResult.Ready).active)
    }

    @Test fun aDesktopWithoutAModuleLeavesTheTileToSystemUi() {
        val sets = mutableListOf<Pair<String, Boolean>>()
        // HyperOS: com.miui.home runs nothing of ours, so it never answers.
        hosts(systemUiActive = false, launcherActive = false, sets, launcher = "com.miui.home", launcherAnswers = false)
        var shown: TriggerCoordinator.RequestResult? = null
        TriggerCoordinator.queryState(context) { shown = it }
        shadowOf(Looper.getMainLooper()).idle()
        var toggled: TriggerCoordinator.RequestResult? = null
        TriggerCoordinator.requestToggle(context, TriggerSource.QUICK_SETTINGS_TILE) { toggled = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse((shown as TriggerCoordinator.RequestResult.Ready).active)
        assertTrue((toggled as TriggerCoordinator.RequestResult.Ready).active)
        assertEquals(TriggerBroadcastContract.SYSTEM_UI_PACKAGE to true, sets.single { it.first == TriggerBroadcastContract.SYSTEM_UI_PACKAGE })
    }

    @Test fun aSilentColorOsLauncherStillBlocksTheToggle() {
        val sets = mutableListOf<Pair<String, Boolean>>()
        hosts(systemUiActive = false, launcherActive = false, sets, launcher = "com.android.launcher", launcherAnswers = false)
        var toggled: TriggerCoordinator.RequestResult? = null
        TriggerCoordinator.requestToggle(context, TriggerSource.QUICK_SETTINGS_TILE) { toggled = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(toggled is TriggerCoordinator.RequestResult.NotReady)
        assertTrue(sets.isEmpty())
    }
}
