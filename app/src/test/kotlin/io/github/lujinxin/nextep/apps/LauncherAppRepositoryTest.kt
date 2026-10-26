package io.github.lujinxin.nextep.apps

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherAppRepositoryTest {
    /** MT Manager's alternate icons are aliases disabled in the manifest and enabled at runtime. */
    @Test
    fun `launcher entry disabled in its manifest but enabled at runtime is listed`() {
        val context = RuntimeEnvironment.getApplication()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(context.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "bin.mt.plus"; name = "bin.mt.plus.IconAlias"
                exported = true; enabled = false
                nonLocalizedLabel = "MT"
                applicationInfo = ApplicationInfo().apply { packageName = "bin.mt.plus" }
            }
        })

        val entries = LauncherAppRepository(context).load(includeProfiles = false)

        assertTrue(entries.any { it.component.flattenToShortString() == "bin.mt.plus/.IconAlias" })
    }
}
