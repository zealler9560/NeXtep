package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.WindowManager
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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WorkspaceSwapAnimatorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun preview(animator: WorkspaceSwapAnimator) = animator.javaClass.getDeclaredField("overlay")
        .apply { isAccessible = true }.get(animator) as? View
    private fun WorkspaceSwapAnimator(context: android.content.Context) = WorkspaceSwapAnimator(context) { WorkbenchAppearance.DARK }

    private fun shown(animator: WorkspaceSwapAnimator) = preview(animator)?.visibility == View.VISIBLE

    @Test fun contentExpansionUsesOneCapturableOverlayAndReleasesEveryAppPreviewOnCancel() {
        val animator = WorkspaceSwapAnimator(context)
        val bitmaps = List(5) { Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888) }
        val frames = bitmaps.mapIndexed { index, bitmap -> WorkspaceSwapAnimator.Frame(bitmap,
            RectF(260f, index * 80f, 300f, index * 80f + 80), RectF(0f, 100f, 240f, 580f)) }
        var moved = false
        animator.animateFrames(frames, frames.map { it.from }) { moved = true }
        val view = checkNotNull(preview(animator))
        assertTrue(shown(animator))
        // ColorOS 16 blurs the control centre from a screen capture, where a secure window is black.
        assertEquals(0, (view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE)
        animator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(moved)
        assertTrue(bitmaps.all { it.isRecycled })
        assertFalse(shown(animator))
    }

    @Test fun theOverlayWindowIsAddedOnceAndKeptHiddenBetweenExchanges() {
        val animator = WorkspaceSwapAnimator(context)
        val rect = RectF(0f, 0f, 40f, 80f)
        animator.animate(null, null, true, rect, rect, rect) { }
        val first = checkNotNull(preview(animator))
        animator.cancel()
        assertSame(first, preview(animator))
        assertEquals(View.INVISIBLE, first.visibility)
        animator.animate(null, null, true, rect, rect, rect) { }
        assertSame(first, preview(animator))
        assertTrue(shown(animator))
        animator.release()
        assertNull(preview(animator))
    }

    @Test fun theMoveWaitsForTheFinalFrameAndTheRevealCutsInOneFrame() {
        val animator = WorkspaceSwapAnimator(context)
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        var moves = 0
        var reveal: (() -> Unit)? = null
        val small = RectF(260f, 100f, 300f, 180f)
        val main = RectF(0f, 100f, 240f, 580f)
        animator.animate(source, null, false, small, small, main) {
            moves++
            reveal = it
        }
        // Still animating at 250 ms of 300.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(0, moves)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550))
        assertEquals(1, moves)
        assertTrue(shown(animator))
        assertFalse(source.isRecycled)
        checkNotNull(reveal).invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        // No fade: hidden at full opacity, its frames released.
        assertFalse(shown(animator))
        assertEquals(1f, checkNotNull(preview(animator)).alpha)
        assertTrue(source.isRecycled)
    }

    @Test fun cancelBeforeTheMoveDoesNotLaunchAnAppAndReleasesFrames() {
        val animator = WorkspaceSwapAnimator(context)
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        val rect = RectF(0f, 0f, 40f, 80f)
        animator.animate(source, null, false, rect, rect, rect) { fail("Cancelled transition moved a task") }
        animator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(shown(animator))
        assertTrue(source.isRecycled)
    }

    @Test fun unavailableCaptureStillProvidesMotionAndCannotRevealANewerTransition() {
        val animator = WorkspaceSwapAnimator(context)
        val rect = RectF(0f, 0f, 40f, 80f)
        var oldReveal: (() -> Unit)? = null
        animator.animate(null, null, true, rect, rect, RectF(0f, 0f, 240f, 480f)) { oldReveal = it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(shown(animator))
        animator.cancel()
        animator.animate(null, null, true, rect, rect, rect) { }
        checkNotNull(oldReveal).invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(shown(animator))
        animator.cancel()
    }

    @Test fun aPaneCountsAsArrivedOnlyWithANewPicture() {
        val outgoing = IntArray(144) { if (it % 2 == 0) 40 else 200 }
        val incoming = IntArray(144) { if (it % 3 == 0) 230 else 20 }
        assertFalse(pictureArrived(outgoing.copyOf(), outgoing))
        assertTrue(pictureArrived(incoming, outgoing))
        // A blank frame or no frame at all is the display still settling.
        assertFalse(pictureArrived(IntArray(144) { 12 }, outgoing))
        assertFalse(pictureArrived(IntArray(0), outgoing))
        // A pane that cannot tell (a mirror shows the app) does not hold the cut.
        assertTrue(pictureArrived(null, outgoing))
        assertTrue(pictureArrived(incoming, null))
    }

    @Test fun emptySlotPreviewsMatchTheLiveCardInEitherAppearanceInsteadOfAFixedDarkBox() {
        for (theme in WorkbenchAppearance.entries) {
            val card = RectF(10f, 10f, 70f, 130f)
            val cover = RectF(100f, 0f, 160f, 120f)
            val view = SwapPreviewView(context, listOf(WorkspaceSwapAnimator.Frame(null, card, card, empty = true)),
                listOf(cover), theme)
            view.progress = 1f
            view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 200, 150)
            val image = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            // Before the wallpaper is captured, cards and covers stay opaque over the stationary tasks.
            assertEquals(theme.card, image.getPixel(16, 16))
            assertEquals(theme.opaquePanel, image.getPixel(130, 60))
            val live = io.github.lujinxin.nextep.display.TaskSwitcherView(context, 0).apply { setAppearance(theme) }
            assertEquals(theme.slotCard, (live.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
            // The plus badge is present in the moving card, not only after the fade.
            val centre = image.getPixel(40, 70)
            assertNotEquals(theme.card, centre)
            assertTrue(android.graphics.Color.alpha(centre) == 255)
            // With the frosted backdrop, covers show it and the empty card is the live slot's faint tone over it.
            val backdrop = android.graphics.Color.RED
            val frosted = SwapPreviewView(context, listOf(WorkspaceSwapAnimator.Frame(null, card, card, empty = true)),
                listOf(cover), theme, android.graphics.drawable.ColorDrawable(backdrop))
            frosted.progress = 1f
            frosted.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY))
            frosted.layout(0, 0, 200, 150)
            image.eraseColor(0)
            frosted.draw(android.graphics.Canvas(image))
            assertEquals(backdrop, image.getPixel(130, 60))
            val expected = androidx.core.graphics.ColorUtils.compositeColors(theme.slotCard, backdrop)
            val drawn = image.getPixel(16, 16)
            for (shift in intArrayOf(0, 8, 16, 24)) {
                assertTrue(kotlin.math.abs((expected ushr shift and 0xFF) - (drawn ushr shift and 0xFF)) <= 2)
            }
        }
    }
}
