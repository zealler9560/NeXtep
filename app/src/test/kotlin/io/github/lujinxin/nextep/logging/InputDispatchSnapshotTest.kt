package io.github.lujinxin.nextep.logging

import org.junit.Assert.*
import org.junit.Test

class InputDispatchSnapshotTest {
    private val dump = """
        Input Reader State:
          device secret serial=private
        Input Dispatcher State:
          DispatchEnabled: true
          DispatchFrozen: false
          FocusedWindows:
            displayId=0, name='abc com.example.driver/.Order'
          TouchStatesByDisplay:
            displayId=0, down=true, split=false
              0: name='abc com.example.driver/.Order', targetFlags=FOREGROUND | AS_IS, pointerIds=0
                DeviceId(-1):[touchingPointers=[Pointer(id=0, FINGER)], pilferingPointerIds=00001]
          Display: 0
            logicalSize=1220x2712
            Windows:
              0: name=abc com.example.driver/.Order, id=12, displayId=0, inputConfig=TRUSTED_OVERLAY | WATCH_OUTSIDE_TOUCH, alpha=1, frame=[259,636][1220,2712], globalScale=1, applicationInfo.name=private customer, touchableRegion=[259,636][1220,2712], ownerPid=123, ownerUid=10001, token=secret, touchOcclusionMode=BLOCK_UNTRUSTED
              transform (ROT_0) (SCALE | TRANSLATE)
                  1.2707 0.0000 -329.1111
                  0.0000 1.2707 -808.1
                  0.0000 0.0000 1.0000
              1: name=private customer https://private.example/order, id=13, displayId=0, inputConfig=NOT_TOUCHABLE, alpha=0.5, frame=[0,0][1220,2712], globalScale=1, touchableRegion=<empty>, ownerPid=456, ownerUid=10002, token=secret, touchOcclusionMode=USE_OPACITY
          RecentQueue:
            secret key event text
    """.trimIndent()

    @Test fun preservesActualTransformsTargetsOcclusionAndRegionsWithoutContents() {
        val result = InputDispatchSnapshot.sanitize(dump)
        assertTrue(result.contains("TouchStatesByDisplay w="))
        assertTrue(result.contains("flags=FOREGROUND | AS_IS"))
        assertTrue(result.contains("pilferingPointerIds=00001"))
        assertTrue(result.contains("pkg=com.example.driver"))
        assertTrue(result.contains("frame=[259,636][1220,2712]"))
        assertTrue(result.contains("touchableRegion=[259,636][1220,2712]"))
        assertTrue(result.contains("1.2707 0.0000 -329.1111"))
        assertTrue(result.contains("touchOcclusionMode=USE_OPACITY"))
        for (private in listOf("secret", "private", "customer", "https", "/.Order", "pointerIds"))
            assertFalse(private, result.contains(private))
        assertEquals(setOf("com.example.driver"), InputDispatchSnapshot.packages(dump))
    }

    @Test fun reportsPermissionAndFormatFailureInsteadOfAnEmptySuccess() {
        assertTrue(InputDispatchSnapshot.sanitize("Permission Denial").contains("unavailable"))
    }

    @Test fun identifiesOnlyKnownSystemBarRolesWithoutExposingArbitraryTitles() {
        val result = InputDispatchSnapshot.sanitize("Input Dispatcher State:\n" +
            "  0: name=abc StatusBar, id=7, displayId=0\n" +
            "  1: name=def NotificationShade, id=8, displayId=0\n" +
            "  2: name=aaa private message, id=9, displayId=0")
        assertTrue(result.contains("role=StatusBar"))
        assertTrue(result.contains("role=NotificationShade"))
        assertFalse(result.contains("private message"))
    }

    @Test fun boundsOutputAndOmitsDuplicatedOemSection() {
        assertEquals(InputDispatchSnapshot.sanitize(dump), InputDispatchSnapshot.sanitize("$dump\n$dump"))
        val huge = "Input Dispatcher State:\n" + (1..1000).joinToString("\n") { "Display: $it" }
        assertTrue(InputDispatchSnapshot.sanitize(huge).lines().size <= 301)
    }
}
