package io.github.lujinxin.nextep.launcher

import android.graphics.Matrix
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TouchCoordinateMapperTest {
    @Test fun gestureProxyHitsTheVisibleBottomIconAndRestoresTheCallersCoordinates() {
        val event = MotionEvent.obtain(10L, 20L, MotionEvent.ACTION_DOWN, 410f, 2480f, 0)
        event.offsetLocation(-10f, -600f)
        val localToScreen = Matrix().apply {
            setScale(.8f, .8f)
            postTranslate(10f, 600f)
        }
        val restore = TouchCoordinateMapper.toLocal(event, localToScreen)
        assertNotNull(restore)
        assertEquals(500f, event.x, .001f)
        assertEquals(2350f, event.y, .001f)
        assertEquals(410f, event.rawX, .001f)
        assertEquals(2480f, event.rawY, .001f)
        event.transform(restore!!)
        assertEquals(400f, event.x, .001f)
        assertEquals(1880f, event.y, .001f)
        assertEquals(410f, event.rawX, .001f)
        assertEquals(2480f, event.rawY, .001f)
        event.recycle()
    }

    @Test fun currentViewMatrixWinsOverCachedOriginForEveryPointerAndHistoricalSample() {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = .6f; size = .1f
        }
        val event = MotionEvent.obtain(10L, 20L, MotionEvent.ACTION_MOVE, 2, properties,
            arrayOf(coords(410f, 2480f), coords(810f, 2080f)), 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        event.addBatch(30L, arrayOf(coords(450f, 2440f), coords(850f, 2040f)), 0)
        // The gesture consumer cached this origin before the native return animation moved it.
        event.offsetLocation(-20f, -550f)
        val localToScreen = Matrix().apply {
            setScale(.8f, .5f)
            postTranslate(10f, 600f)
        }
        val restore = TouchCoordinateMapper.toLocal(event, localToScreen)!!
        assertEquals(550f, event.getX(0), .001f)
        assertEquals(3680f, event.getY(0), .001f)
        assertEquals(1050f, event.getX(1), .001f)
        assertEquals(2880f, event.getY(1), .001f)
        assertEquals(500f, event.getHistoricalX(0, 0), .001f)
        assertEquals(3760f, event.getHistoricalY(0, 0), .001f)
        assertEquals(1, event.getPointerId(1))
        assertEquals(.6f, event.getPressure(1), .001f)
        event.transform(restore)
        assertEquals(430f, event.x, .001f)
        assertEquals(1890f, event.y, .001f)
        assertEquals(790f, event.getHistoricalX(1, 0), .001f)
        assertEquals(1530f, event.getHistoricalY(1, 0), .001f)
        event.recycle()
    }

    @Test fun singularMatrixLeavesTheNativeEventUntouched() {
        val event = MotionEvent.obtain(10L, 20L, MotionEvent.ACTION_DOWN, 410f, 2480f, 0)
        assertNull(TouchCoordinateMapper.toLocal(event, Matrix().apply { setScale(0f, 0f) }))
        assertEquals(410f, event.x, 0f)
        assertEquals(2480f, event.y, 0f)
        event.recycle()
    }
}
