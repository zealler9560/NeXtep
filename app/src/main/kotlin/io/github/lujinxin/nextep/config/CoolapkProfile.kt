package io.github.lujinxin.nextep.config

import android.content.Context
import android.content.Intent
import android.net.Uri

internal object CoolapkProfile {
    const val NICKNAME = "Shisan人"
    const val URL = "https://www.coolapk.com/u/27844713"
    const val PACKAGE = "com.coolapk.market"

    fun open(context: Context): Boolean {
        for (target in listOf(PACKAGE, null)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL))
                    .setPackage(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: android.content.ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    // Kept in the module's private data: survives upgrades, disappears with an uninstall.
    @Synchronized fun initialize(context: Context) {
        val prefs = context.getSharedPreferences("coolapk_intro", Context.MODE_PRIVATE)
        if (prefs.contains("pending")) return
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        check(prefs.edit().putBoolean("pending", info.firstInstallTime == info.lastUpdateTime).commit())
    }

    @Synchronized fun claimFirstEntry(context: Context): Boolean {
        initialize(context)
        val prefs = context.getSharedPreferences("coolapk_intro", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pending", false)) return false
        return prefs.edit().putBoolean("pending", false).commit()
    }
}
