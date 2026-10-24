package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisibleGlyphBoundsTest {
    @Test fun boundsCoverOpaquePixelsOnlyAndIgnoreFaintShadow() {
        val icon = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        assertNull(visibleGlyphBounds(icon))
        // A glyph inset by a quarter (ColorOS 17), with a faint shadow below it.
        for (y in 10 until 30) for (x in 10 until 30) icon.setPixel(x, y, Color.GREEN)
        for (x in 10 until 30) icon.setPixel(x, 33, Color.argb(0x18, 0, 0, 0))
        assertEquals(Rect(10, 10, 30, 30), visibleGlyphBounds(icon))
        // A full-bleed glyph (ColorOS 16) spans the canvas.
        icon.eraseColor(Color.BLUE)
        assertEquals(Rect(0, 0, 40, 40), visibleGlyphBounds(icon))
    }
}
