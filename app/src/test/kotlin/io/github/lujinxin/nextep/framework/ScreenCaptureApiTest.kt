package io.github.lujinxin.nextep.framework

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceControl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36, 37])
class ScreenCaptureApiTest {
    @Test fun resolvesSynchronousCaptureAndMatchingBuilderOnEachFramework() {
        val api = TaskSurfaceCompat.screenCaptureClass
        val builder = Class.forName("${api.name}\$LayerCaptureArgs\$Builder")
        builder.getConstructor(SurfaceControl::class.java)
        builder.getMethod("setSourceCrop", Rect::class.java)
        builder.getMethod("setFrameScale", Float::class.javaPrimitiveType)
        builder.getMethod("setChildrenOnly", Boolean::class.javaPrimitiveType)
        val args = builder.getMethod("build").returnType
        val buffer = api.getMethod("captureLayers", args).returnType
        assertEquals(Bitmap::class.java, buffer.getMethod("asBitmap").returnType)
    }
}
