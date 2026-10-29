package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class SearchPauseBarrierTest {
    private fun results(offset: Int = 0) = IntArray(144) { if (it % 9 in 2..6) 100 + offset else 230 }

    @Test fun allBatchWindowsMustHaveSettledContentBeforeAnyIsReleased() {
        val gate = SearchPauseBarrier()
        gate.protect(1, 0); gate.protect(2, 0)
        gate.observe(1, results(), 1000); gate.observe(1, results(), 10_000)
        gate.observe(2, IntArray(144) { 255 }, 10_000)
        assertTrue(gate.releaseIfReady().isEmpty())
        gate.observe(2, results(), 11_000)
        gate.observe(2, results(), 13_999)
        assertTrue(gate.releaseIfReady().isEmpty())
        gate.observe(2, results(), 14_000)
        assertEquals(setOf(1, 2), gate.releaseIfReady())
        assertFalse(gate.contains(1))
    }

    @Test fun aToolbarOrMissingCaptureNeverBecomesReadyEvenAfterLongDelay() {
        val gate = SearchPauseBarrier()
        gate.protect(1, 0)
        val header = IntArray(144) { if (it < 27) it * 7 else 255 }
        for (grid in listOf(null, intArrayOf(), IntArray(144), header)) {
            gate.observe(1, grid, 1000); gate.observe(1, grid, 120_000)
            assertTrue(gate.releaseIfReady().isEmpty())
        }
        assertFalse(gate.shouldSample(1, 60_000))
        assertTrue(gate.contains(1)) // A timeout stops probes, never forces a pause.
    }

    @Test fun lateContentChangesResetStabilityAndTheMinimumLoadTimeIsRespected() {
        val gate = SearchPauseBarrier()
        gate.protect(1, 0)
        gate.observe(1, results(), 0); gate.observe(1, results(), 4000)
        assertTrue(gate.releaseIfReady().isEmpty())
        gate.observe(1, results(50), 9900); gate.observe(1, results(50), 10_000)
        assertTrue(gate.releaseIfReady().isEmpty())
        gate.observe(1, results(50), 12_900)
        assertEquals(setOf(1), gate.releaseIfReady())
    }

    @Test fun leavingTasksAndCancelledSessionsDoNotBlockOrReleaseNewOccupants() {
        val gate = SearchPauseBarrier()
        gate.protect(1, 0); gate.protect(2, 0)
        gate.observe(1, results(), 1000); gate.observe(1, results(), 10_000)
        gate.remove(2)
        assertEquals(setOf(1), gate.releaseIfReady())
        gate.protect(3, 0); gate.clear()
        gate.observe(3, results(), 20_000)
        assertTrue(gate.releaseIfReady().isEmpty())
    }
}
