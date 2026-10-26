package io.github.lujinxin.nextep.config

import android.content.Context
import android.content.ContextWrapper
import io.github.lujinxin.nextep.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLanguageTest {
    /** A host (SystemUI) whose module package context cannot be built must get a blank label, not a crash. */
    @Test
    fun hostLookupWithoutModuleResourcesDegradesToBlank() {
        val host = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName(): String = "com.android.systemui"
            override fun createPackageContext(packageName: String, flags: Int): Context =
                throw IllegalStateException("module APK replaced")
        }
        assertEquals("", AppLanguage.string(host, R.string.workbench_compare))
    }
}
