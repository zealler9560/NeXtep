package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwapPreviewViewTest {
    @Test fun returningVideoKeepsItsFirstFrameAndEndsWithTheRelayoutIncludingLetterbox() {
        val old = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val fresh = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val from = RectF(0f, 0f, 200f, 100f)
        val to = RectF(0f, 0f, 200f, 200f)
        val view = SwapPreviewView(RuntimeEnvironment.getApplication(),
            listOf(WorkspaceSwapAnimator.Frame(old, from, to, endBitmap = fresh)),
            emptyList(), WorkbenchAppearance.DARK)
        view.layout(0, 0, 200, 200)
        fun sample(progress: Float, x: Int, y: Int): Int {
            view.progress = progress
            val output = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            return output.getPixel(x, y).also { output.recycle() }
        }
        assertEquals(Color.RED, sample(0f, 100, 50))
        val middle = sample(0.5f, 100, 60)
        assertTrue(Color.red(middle) in 126..129)
        assertTrue(Color.blue(middle) in 126..129)
        assertEquals(Color.BLUE, sample(1f, 100, 50))
        // The departing landscape frame must not remain outside the portrait page at the endpoint.
        assertEquals(WorkbenchAppearance.DARK.card, sample(1f, 25, 100))
        view.clear()
        assertTrue(old.isRecycled)
        assertTrue(fresh.isRecycled)
    }

    @Test fun videoAndReturningPageShareTheTopAnchorDuringExpansion() {
        val old = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val fresh = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            Canvas(this).drawRect(0f, 0f, 100f, 50f, android.graphics.Paint().apply { color = Color.GREEN })
        }
        val view = SwapPreviewView(RuntimeEnvironment.getApplication(), listOf(WorkspaceSwapAnimator.Frame(
            old, RectF(0f, 0f, 200f, 100f), RectF(0f, 0f, 200f, 400f),
            topFill = true, endBitmap = fresh)), emptyList(), WorkbenchAppearance.DARK)
        view.layout(0, 0, 200, 400)
        view.progress = 0.5f
        val output = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        val video = output.getPixel(100, 50)
        assertTrue(Color.red(video) in 126..129)
        assertTrue(Color.green(video) in 126..129)
        assertEquals(Color.BLUE, output.getPixel(100, 180))
        output.recycle()
        view.clear()
    }

    @Test fun sharedPreviewIsRetainedUntilTheWholeOverlayIsCleared() {
        val shared = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val fresh = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val bounds = RectF(0f, 0f, 20f, 20f)
        val view = SwapPreviewView(RuntimeEnvironment.getApplication(), listOf(
            WorkspaceSwapAnimator.Frame(shared, bounds, bounds, endBitmap = fresh),
            WorkspaceSwapAnimator.Frame(shared, bounds, bounds)), emptyList(), WorkbenchAppearance.DARK)
        assertFalse(shared.isRecycled)
        view.clear()
        view.clear()
        assertTrue(shared.isRecycled)
        assertTrue(fresh.isRecycled)
    }
}
