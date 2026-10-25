package io.github.lujinxin.nextep.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.PowerManager
import androidx.core.graphics.ColorUtils
import io.github.lujinxin.nextep.logging.NeXtepLog

object WorkspaceWallpaperBackdrop {
    fun canCapture(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }.getOrDefault(false)

    // This code executes inside the hooked SystemUI process, whose Context owns wallpaper access.
    @SuppressLint("MissingPermission")
    fun identity(context: Context): String? = runCatching {
        val manager = WallpaperManager.getInstance(context)
        "${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}:${manager.wallpaperInfo?.component}"
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun capture(context: Context, width: Int, height: Int): Bitmap? = runCatching {
        // The wallpaper surface on keyguard may be a different lock-screen image.
        // Never replace the desktop cache with it or with a screen-off frame.
        if (!canCapture(context)) return@runCatching null
        // ColorOS 17 uses a live service even for some desktop wallpaper presets.
        // WallpaperManager.drawable can then contain the previous static wallpaper.
        // Capture only the wallpaper surface, never the screen's apps or overlays.
        captureRenderedWallpaper()?.let { snapshot ->
            val fitted = try {
                val software = if (snapshot.config == Bitmap.Config.HARDWARE) {
                    snapshot.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
                } else snapshot
                try {
                    fit(software, width, height)
                } finally {
                    if (software !== snapshot) software.recycle()
                }
            } finally {
                snapshot.recycle()
            }
            visibleOrNull(fitted, "surface")?.let { return@runCatching it }
        }
        val manager = WallpaperManager.getInstance(context)
        // A live service may need a few frames after FLAG_SHOW_WALLPAPER is set.
        // Retain the last rendered frame while the controller retries, rather than
        // displaying WallpaperManager's unrelated previous static image.
        if (manager.wallpaperInfo != null) {
            NeXtepLog.info("wallpaper_backdrop", "No surface frame; live wallpaper ${manager.wallpaperInfo?.component}")
            return@runCatching null
        }
        manager.forgetLoadedWallpaper()
        val wallpaper = manager.drawable?.constantState?.newDrawable(context.resources)?.mutate()
            ?: return@runCatching null
        val sampleWidth = width.coerceAtLeast(1)
        val sampleHeight = height.coerceAtLeast(1)
        val sampled = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        val sourceWidth = wallpaper.intrinsicWidth.takeIf { it > 0 } ?: width
        val sourceHeight = wallpaper.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = maxOf(
            sampleWidth.toFloat() / sourceWidth,
            sampleHeight.toFloat() / sourceHeight,
        )
        val drawWidth = (sourceWidth * scale).toInt()
        val drawHeight = (sourceHeight * scale).toInt()
        val left = (sampleWidth - drawWidth) / 2
        val top = (sampleHeight - drawHeight) / 2
        wallpaper.setBounds(left, top, left + drawWidth, top + drawHeight)
        wallpaper.draw(Canvas(sampled))
        visibleOrNull(sampled, "static")
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Unable to capture desktop wallpaper", error)
    }.getOrNull()

    /**
     * Some ROMs (a realme GT8 report) hand back a hidden or protected wallpaper surface as solid
     * black; blurred under the translucent panels it turned the whole workspace black. Such a
     * frame is dropped, so the panels keep their opaque fallback tone and the capture retries.
     */
    private fun visibleOrNull(bitmap: Bitmap?, source: String): Bitmap? {
        if (bitmap == null) return null
        if (!isBlank(bitmap)) {
            NeXtepLog.info("wallpaper_backdrop", "Captured source=$source ${bitmap.width}x${bitmap.height}")
            return bitmap
        }
        NeXtepLog.warn("wallpaper_backdrop", "Dropped a blank $source frame ${bitmap.width}x${bitmap.height}")
        bitmap.recycle()
        return null
    }

    /** True when a 9x9 grid of samples is all (near) black: no picture, not a dark wallpaper. */
    internal fun isBlank(bitmap: Bitmap): Boolean = (0 until 9).all { row ->
        (0 until 9).all { column ->
            val pixel = bitmap.getPixel(((column + 0.5f) * bitmap.width / 9).toInt().coerceIn(0, bitmap.width - 1),
                ((row + 0.5f) * bitmap.height / 9).toInt().coerceIn(0, bitmap.height - 1))
            maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 8
        }
    }

    fun fit(source: Bitmap, width: Int, height: Int): Bitmap? = runCatching {
        val fitted = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val scale = maxOf(fitted.width.toFloat() / source.width, fitted.height.toFloat() / source.height)
            val canvas = Canvas(fitted)
            canvas.translate((fitted.width - source.width * scale) / 2f, (fitted.height - source.height * scale) / 2f)
            canvas.scale(scale, scale)
            canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            fitted
        } catch (error: Throwable) {
            fitted.recycle()
            throw error
        }
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Unable to fit wallpaper snapshot", error)
    }.getOrNull()

    private fun captureRenderedWallpaper(): Bitmap? = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val service = global.getDeclaredMethod("getWindowManagerService").apply {
            isAccessible = true
        }.invoke(null) ?: return@runCatching null
        Class.forName("android.view.IWindowManager")
            .getDeclaredMethod("screenshotWallpaper")
            .apply { isAccessible = true }
            .invoke(service) as? Bitmap
    }.onFailure { error ->
        NeXtepLog.warn("wallpaper_backdrop", "Wallpaper surface capture unavailable", error)
    }.getOrNull()

    /** [fallback] fills the panel until the first capture; behind it is only the black display. */
    fun crop(bitmap: Bitmap?, originX: Int, originY: Int, strength: Int,
        previous: Drawable? = null, fallback: Int = Color.TRANSPARENT): Drawable =
        (previous as? WallpaperCropDrawable ?: WallpaperCropDrawable()).apply {
            update(bitmap, originX, originY, strength.coerceIn(0, 100), fallback)
        }

    fun isLight(bitmap: Bitmap?, originX: Int, originY: Int, width: Int, height: Int): Boolean =
        runCatching {
            if (bitmap == null || bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) {
                return@runCatching false
            }
            var luminance = 0.0
            // The cached frame is software-backed; a small grid avoids copying or scanning it.
            for (y in 0 until 4) {
                for (x in 0 until 8) {
                    val sampleX = (originX + (x + 0.5f) * width / 8).toInt().coerceIn(0, bitmap.width - 1)
                    val sampleY = (originY + (y + 0.5f) * height / 4).toInt().coerceIn(0, bitmap.height - 1)
                    luminance += ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(
                        bitmap.getPixel(sampleX, sampleY), 255,
                    ))
                }
            }
            luminance / 32 > 0.3
        }.getOrDefault(false)

}

private class WallpaperCropDrawable : Drawable() {
    private var bitmap: Bitmap? = null
    private var originX = 0
    private var originY = 0
    private var strength = -1
    private var fallback = Color.TRANSPARENT
    private var density = 0f
    private val sourceRect = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = null
    }

    private var blurredWallpaper: android.graphics.RenderNode? = null

    fun update(source: Bitmap?, x: Int, y: Int, blur: Int, fill: Int) {
        val imageChanged = bitmap !== source
        val blurChanged = strength != blur
        val updatedDensity = android.content.res.Resources.getSystem().displayMetrics.density
        val densityChanged = density != updatedDensity
        if (!imageChanged && !blurChanged && !densityChanged && originX == x && originY == y && fallback == fill) return
        fallback = fill
        bitmap = source
        originX = x
        originY = y
        strength = blur
        density = updatedDensity
        if (source == null || blur == 0) {
            blurredWallpaper?.discardDisplayList()
            blurredWallpaper = null
        } else {
            val newNode = blurredWallpaper == null
            val node = blurredWallpaper ?: android.graphics.RenderNode("NeXtep-FrostedWallpaper")
                .also { blurredWallpaper = it }
            if (imageChanged || newNode) {
                node.setPosition(0, 0, source.width, source.height)
                val recording = node.beginRecording(source.width, source.height)
                try { recording.drawBitmap(source, 0f, 0f, null) }
                finally { node.endRecording() }
            }
            // Strength changes update only the effect, retaining the recorded wallpaper.
            if (blurChanged || newNode || densityChanged) {
                val fraction = strength / 100f
                // 30 dp at the default strength: the wallpaper reads as soft colour fields under text.
                val radius = 120f * fraction * fraction * density
                node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    radius.coerceAtLeast(0.01f), radius.coerceAtLeast(0.01f), android.graphics.Shader.TileMode.CLAMP,
                ))
            }
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val source = bitmap ?: return canvas.drawColor(fallback)
        val left = originX.coerceIn(0, source.width)
        val top = originY.coerceIn(0, source.height)
        val right = (left + bounds.width()).coerceAtMost(source.width)
        val bottom = (top + bounds.height()).coerceAtMost(source.height)
        if (right <= left || bottom <= top) return
        val node = blurredWallpaper
        if (node != null && canvas.isHardwareAccelerated) {
            val save = canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / (right - left), bounds.height().toFloat() / (bottom - top))
            canvas.translate(-left.toFloat(), -top.toFloat())
            node.alpha = paint.alpha / 255f
            canvas.drawRenderNode(node)
            canvas.restoreToCount(save)
        } else {
            sourceRect.set(left, top, right, bottom)
            canvas.drawBitmap(source, sourceRect, bounds, paint)
        }
        // The panel tone drawn over this layer tints the captured wallpaper.
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha == alpha) return
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
