package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Proxy

/**
 * Exists only while a video is pinned: its own input monitor watches the edge bands beside the
 * pinned video and turns an inward swipe there into BACK for the video. With nothing pinned it is
 * not installed at all, so the system gesture and every other path run exactly as before.
 */
internal class WideEdgeBackMonitor(
    private val context: Context,
    private val region: () -> WideEdgeBackGestureDetector.Region?,
    private val sendBack: () -> Boolean,
) {
    private var monitor: Any? = null
    private var receiver: Any? = null

    fun start() {
        if (receiver != null) return
        var newMonitor: Any? = null
        var newReceiver: Any? = null
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY) ?: error("Default display unavailable")
            val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val inputMonitor = managerClass.getMethod(
                "monitorGestureInput", String::class.java, Int::class.javaPrimitiveType,
            ).invoke(manager, "NeXtep-wide-back", Display.DEFAULT_DISPLAY)
            newMonitor = inputMonitor
            val pilfer = inputMonitor.javaClass.getMethod("pilferPointers")
            val detector = WideEdgeBackGestureDetector(context.resources.displayMetrics.density)
            val size = Point()
            val handler = Handler(Looper.getMainLooper())
            val receiverClass = Class.forName(
                "android.view.BatchedInputEventReceiver\$SimpleBatchedInputEventReceiver",
            )
            val listenerClass = Class.forName("${receiverClass.name}\$InputEventListener")
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "NeXtepWideEdgeBackListener"
                    "onInputEvent" -> runCatching {
                        val event = args?.firstOrNull() as? MotionEvent
                        if (event == null || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                            detector.reset()
                            return@runCatching false
                        }
                        val down = event.actionMasked == MotionEvent.ACTION_DOWN
                        @Suppress("DEPRECATION")
                        if (down) display.getRealSize(size)
                        when (detector.observe(event.actionMasked, event.rawX, event.rawY, event.pointerCount,
                            event.eventTime, size.x, if (down) region() else null)) {
                            // Claim the finger at DOWN so the system edge-back never sees it.
                            WideEdgeBackGestureDetector.Result.STARTED -> pilfer.invoke(inputMonitor)
                            WideEdgeBackGestureDetector.Result.CONFIRMED -> handler.post {
                                NeXtepLog.info("wide_back", "Edge swipe back sent=${runCatching(sendBack).getOrDefault(false)}")
                            }
                            else -> Unit
                        }
                        false
                    }.getOrElse { error ->
                        detector.reset()
                        NeXtepLog.warn("wide_back", "Input observation failed open", error)
                        false
                    }
                    else -> null
                }
            }
            newReceiver = receiverClass.getConstructor(
                Class.forName("android.view.InputChannel"), Looper::class.java,
                Choreographer::class.java, listenerClass,
            ).newInstance(
                inputMonitor.javaClass.getMethod("getInputChannel").invoke(inputMonitor),
                Looper.getMainLooper(), Choreographer.getInstance(), listener,
            )
            receiverClass.getMethod("setBatchingEnabled", Boolean::class.javaPrimitiveType)
                .invoke(newReceiver, false)
            monitor = inputMonitor
            receiver = newReceiver
            NeXtepLog.info("wide_back", "Edge-back monitor installed")
        }.onFailure { error ->
            dispose(newReceiver, newMonitor)
            NeXtepLog.warn("wide_back", "Edge-back monitor unavailable", error)
        }
    }

    fun stop() {
        if (receiver == null && monitor == null) return
        dispose(receiver, monitor)
        receiver = null
        monitor = null
        NeXtepLog.info("wide_back", "Edge-back monitor removed")
    }

    private fun dispose(vararg resources: Any?) {
        for (resource in resources) runCatching { resource?.javaClass?.getMethod("dispose")?.invoke(resource) }
    }
}
