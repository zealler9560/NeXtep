package io.github.lujinxin.nextep.logging

import org.junit.Assert.*
import org.junit.Test

class StatusBarLayerSnapshotTest {
    private val dump = """
          Layer [30] abc StatusBar#30
            invisible reason=hidden by parent or layer flag
            bounds={0,0,1080,94} toDisplayTransform={ tx=0.2 ty=512.0 }
            geomLayerTransform (ROT_0) (SCALE)
            0.7866 0.0 0.2
            0.0 0.7866 512.0
            0.0 0.0 1.0
            geomCrop=[0 94 1080 2400] private https://private.example/customer
        Layer Hierarchy
         ROOT
         └─ Display 0 name="private device"#1 flags:0 a:1
            └─ Task=2#20 flags:0 a:1
               └─ abc StatusBar#30 flags:0 a:1
                  └─ VRI-StatusBar#31 flags:0 a:1
            └─ private customer#50 flags:0 a:1
        Offscreen Hierarchy
         └─ StatusBar#60 flags:1 a:0
    """.trimIndent()

    @Test fun capturesOnlyBarAndAncestorsAndShowsCropAndTransform() {
        val result = StatusBarLayerSnapshot.sanitize(dump)
        assertTrue(result.contains("layer=30 parent=20 role=StatusBar"))
        assertTrue(result.contains("layer=20 parent=1 role=Task=2"))
        assertTrue(result.contains("invisible=true"))
        assertTrue(result.contains("geomCrop=[0 94 1080 2400]"))
        assertTrue(result.contains("matrix=0.0 0.7866 512.0"))
        for (secret in listOf("private", "customer", "https", "layer=50", "layer=60")) assertFalse(result.contains(secret))
    }

    @Test fun explicitRelativeParentWinsOverVisualIndentationAndCyclesTerminate() {
        val result = StatusBarLayerSnapshot.sanitize(dump.replace("abc StatusBar#30 flags", "(Relative) StatusBar#30 parent=30 flags"))
        assertTrue(result.contains("layer=30 parent=30 role=StatusBar relative=true"))
        assertTrue(result.length < 2000)
    }

    @Test fun permissionFailureAndUnknownFormatsNeverLeakRawDump() {
        for (value in listOf("Permission Denial secret", "private content", "Layer Hierarchy\n ROOT")) {
            assertEquals("status bar layer ancestry unavailable (permission or ROM format)", StatusBarLayerSnapshot.sanitize(value))
        }
    }
}
