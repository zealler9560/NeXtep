package io.github.lujinxin.nextep.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoolapkProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun installation(first: Long, updated: Long) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime = first
        info.lastUpdateTime = updated
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test fun freshInstallClaimsOnlyOnceAcrossReinitializationAndUpgrade() {
        installation(100, 100)
        CoolapkProfile.initialize(context)
        assertTrue(CoolapkProfile.claimFirstEntry(context))
        CoolapkProfile.initialize(context)
        assertFalse(CoolapkProfile.claimFirstEntry(context))
        installation(100, 200)
        assertFalse(CoolapkProfile.claimFirstEntry(context))
    }

    @Test fun legacyUpgradeDoesNotOpenProfile() {
        installation(100, 200)
        assertFalse(CoolapkProfile.claimFirstEntry(context))
    }

    @Test fun notYetEnteredFreshInstallRemainsPendingThroughUpgrade() {
        installation(100, 100)
        CoolapkProfile.initialize(context)
        installation(100, 200)
        assertTrue(CoolapkProfile.claimFirstEntry(context))
    }

    @Test fun reinstalledPrivateDataCanClaimAgain() {
        installation(100, 100)
        assertTrue(CoolapkProfile.claimFirstEntry(context))
        context.getSharedPreferences("coolapk_intro", Context.MODE_PRIVATE).edit().clear().commit()
        installation(200, 200)
        assertTrue(CoolapkProfile.claimFirstEntry(context))
    }

    @Test fun prefersCoolapkAndFallsBackToWebWithoutChangingProfile() {
        val attempts = mutableListOf<Intent>()
        val target = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                attempts += intent
                if (intent.`package` != null) throw ActivityNotFoundException()
            }
        }
        assertTrue(CoolapkProfile.open(target))
        assertEquals(listOf(CoolapkProfile.PACKAGE, null), attempts.map { it.`package` })
        assertTrue(attempts.all { it.dataString == CoolapkProfile.URL && it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
    }

    @Test fun unavailableHandlersDoNotCrash() {
        val target = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(CoolapkProfile.open(target))
    }
}
