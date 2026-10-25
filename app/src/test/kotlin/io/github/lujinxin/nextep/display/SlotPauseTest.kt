package io.github.lujinxin.nextep.display

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import io.github.lujinxin.nextep.workspace.PauseRecommendations
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SlotPauseTest {
    @Test fun onlyAChosenAppPausesAndPlaybackACallOrTheWidePaneKeepItLive() {
        assertEquals(SlotPauseRules.NOT_CHOSEN, SlotPauseRules.blocker(chosen = false, wide = true, inCall = true, playing = true))
        assertNull(SlotPauseRules.blocker(chosen = true, wide = false, inCall = false, playing = false))
        assertEquals("wide", SlotPauseRules.blocker(chosen = true, wide = true, inCall = false, playing = false))
        assertEquals("call", SlotPauseRules.blocker(chosen = true, wide = false, inCall = true, playing = false))
        assertEquals("media", SlotPauseRules.blocker(chosen = true, wide = false, inCall = false, playing = true))
    }

    @Test fun gamesChatVideoAndMapsAreNeverRecommended() {
        assertTrue(PauseRecommendations.recommended("com.taobao.taobao", ApplicationInfo.CATEGORY_UNDEFINED, game = false))
        assertTrue(PauseRecommendations.recommended("com.example.reader", ApplicationInfo.CATEGORY_NEWS, game = false))
        assertFalse(PauseRecommendations.recommended("com.example.unknown", ApplicationInfo.CATEGORY_UNDEFINED, game = false))
        assertFalse(PauseRecommendations.recommended("com.taobao.taobao", ApplicationInfo.CATEGORY_UNDEFINED, game = true))
        listOf(ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_MAPS,
            ApplicationInfo.CATEGORY_AUDIO).forEach {
            assertFalse(PauseRecommendations.recommended("com.taobao.taobao", it, game = false))
        }
        @Suppress("DEPRECATION")
        assertTrue(PauseRecommendations.isGame(ApplicationInfo().apply { flags = ApplicationInfo.FLAG_IS_GAME }))
        assertTrue(PauseRecommendations.isGame(ApplicationInfo().apply { category = ApplicationInfo.CATEGORY_GAME }))
        assertFalse(PauseRecommendations.isGame(ApplicationInfo()))
    }

    @Test fun aHeldCoverOutlivesStateRefreshesAndTransitionsGetACopyOfIt() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        // Attached, so the release's fade-out runs as it does in the sidebar window.
        val view = TaskSwitcherView(activity, 0).also(activity::setContentView)
        val occupied = SlotState.Occupied(7, 42, ComponentName("com.example", "com.example.Main"))
        view.showState(occupied)
        val cover = Bitmap.createBitmap(30, 60, Bitmap.Config.ARGB_8888)
        view.holdCover(cover)
        // A busy toggle re-shows the state; before, that cleared any cover 600 ms later.
        view.setBusy(true)
        view.setBusy(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        val coverView = (0 until view.childCount).map(view::getChildAt).filterIsInstance<android.widget.ImageView>().single()
        assertEquals(View.VISIBLE, coverView.visibility)
        assertTrue(view.isCoverHeld())
        val preview = checkNotNull(view.captureProtectedPreview())
        assertNotSame(cover, preview)
        assertEquals(30, preview.width)
        // The swap animation recycles its frames; the held picture must survive that.
        preview.recycle()
        assertFalse(cover.isRecycled)
        view.releaseCover()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(view.isCoverHeld())
        assertEquals(View.GONE, coverView.visibility)
    }
}
