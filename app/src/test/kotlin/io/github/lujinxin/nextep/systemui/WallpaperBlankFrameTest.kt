package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperBlankFrameTest {
    @Test fun onlyASolidBlackFrameIsBlankNotADarkWallpaper() {
        val frame = Bitmap.createBitmap(90, 180, Bitmap.Config.ARGB_8888)
        frame.eraseColor(Color.BLACK)
        assertTrue(WorkspaceWallpaperBackdrop.isBlank(frame))
        // A dark wallpaper still has a picture.
        frame.eraseColor(Color.rgb(18, 22, 34))
        assertFalse(WorkspaceWallpaperBackdrop.isBlank(frame))
        // Black with a lit area (a night sky with a moon) is a picture too.
        frame.eraseColor(Color.BLACK)
        for (y in 60 until 120) for (x in 30 until 60) frame.setPixel(x, y, Color.rgb(220, 220, 200))
        assertFalse(WorkspaceWallpaperBackdrop.isBlank(frame))
    }
}
