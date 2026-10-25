package io.github.lujinxin.nextep.display

import android.content.Context
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * An invisible window across the top of a pane's display that provides the status bar inset, as the
 * status bar window does on the phone's display. Apps then keep the same blank band on both, so a task
 * moving between them lays out nothing anew; the card crops the band like the main window does.
 */
class SlotStatusBarInsets(private val context: Context, private val slotIndex: Int) {
    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var height = 0
    // The same provider for the window's lifetime: WindowManager rejects a changed one on relayout.
    private val providers by lazy {
        val type = Class.forName("android.view.InsetsFrameProvider")
        val provider = type.getConstructor(Any::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(android.os.Binder(), 0, WindowInsets.Type.statusBars())
        java.lang.reflect.Array.newInstance(type, 1).also { java.lang.reflect.Array.set(it, 0, provider) }
    }

    /** Keeps a window of [statusBarHeight] on [display]; none when either is missing. */
    fun sync(display: Display?, statusBarHeight: Int) {
        if (display == null || statusBarHeight <= 0) return detach()
        val current = view
        if (current != null) {
            if (statusBarHeight == height) return
            height = statusBarHeight
            runCatching { windowManager?.updateViewLayout(current, params(statusBarHeight)) }
                .onFailure { NeXtepLog.warn("virtual_display", "Status inset resize failed slot=$slotIndex", it) }
            return
        }
        runCatching {
            val windowContext = context.createDisplayContext(display).createWindowContext(TYPE, null)
            val manager = checkNotNull(windowContext.getSystemService(WindowManager::class.java))
            val added = View(windowContext)
            manager.addView(added, params(statusBarHeight))
            windowManager = manager
            view = added
            height = statusBarHeight
            NeXtepLog.info("virtual_display", "Status inset window slot=$slotIndex display=${display.displayId} height=$statusBarHeight")
        }.onFailure { NeXtepLog.warn("virtual_display", "Status inset window failed slot=$slotIndex", it) }
    }

    fun detach() {
        val current = view ?: return
        runCatching { windowManager?.removeViewImmediate(current) }
        view = null
        windowManager = null
        height = 0
    }

    private fun params(statusBarHeight: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, statusBarHeight, TYPE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        title = "NeXtep-slot-$slotIndex-status"
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        // Hidden field, as SystemUI's own status bar sets it; the frame provided is the window's.
        WindowManager.LayoutParams::class.java.getField("providedInsets").set(this, providers)
    }

    private companion object {
        const val TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }
}
