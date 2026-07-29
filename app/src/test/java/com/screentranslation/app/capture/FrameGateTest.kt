package com.screentranslation.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    private var now = 0L
    private fun gate(intervalMs: Long = 100L) = FrameGate(intervalMs) { now }

    @Test
    fun `rejects a negative interval`() {
        val error = runCatching { FrameGate(-1L) { 0L } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `accepts the very first frame regardless of the clock`() {
        now = 5_000L
        assertNotNull(gate().tryAcquire())
    }

    @Test
    fun `holds the single-flight slot until released`() {
        val gate = gate()

        assertNotNull(gate.tryAcquire())
        now += 10_000L
        assertNull("a second frame must not enter while one is in flight", gate.tryAcquire())

        gate.release()
        assertNotNull("the slot must be reusable after release", gate.tryAcquire())
    }

    @Test
    fun `throttles frames that arrive inside the interval`() {
        val gate = gate(intervalMs = 100L)

        assertNotNull(gate.tryAcquire())
        gate.release()

        now += 99L
        assertNull("99ms < 100ms interval", gate.tryAcquire())

        now += 1L
        assertNotNull("100ms >= 100ms interval", gate.tryAcquire())
    }

    @Test
    fun `a throttled frame does not move the throttle window`() {
        val gate = gate(intervalMs = 100L)

        assertNotNull(gate.tryAcquire())
        gate.release()

        now += 50L
        assertNull(gate.tryAcquire())

        // Still measured from the accepted frame at t=0, not the rejected one.
        now += 50L
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun `a zero interval throttles nothing`() {
        val gate = gate(intervalMs = 0L)

        repeat(5) {
            assertNotNull(gate.tryAcquire())
            gate.release()
        }
    }

    @Test
    fun `drops frames while disabled and resumes when re-enabled`() {
        val gate = gate(intervalMs = 0L)

        gate.setEnabled(false)
        assertNull(gate.tryAcquire())

        gate.setEnabled(true)
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun `disabling does not strand the single-flight slot`() {
        val gate = gate(intervalMs = 0L)

        assertNotNull(gate.tryAcquire())
        gate.setEnabled(false)
        gate.release()

        gate.setEnabled(true)
        assertNotNull("release while disabled must still free the slot", gate.tryAcquire())
    }

    @Test
    fun `invalidate makes in-flight work stale`() {
        val gate = gate(intervalMs = 0L)

        val generation = gate.tryAcquire()
        assertNotNull(generation)
        assertTrue(gate.isCurrent(generation!!))

        gate.invalidate()
        assertFalse("results from before the reset must be discarded", gate.isCurrent(generation))
    }

    @Test
    fun `a frame acquired after invalidate is current again`() {
        val gate = gate(intervalMs = 0L)

        val stale = gate.tryAcquire()
        assertNotNull(stale)
        gate.release()

        gate.invalidate()
        val fresh = gate.tryAcquire()
        assertNotNull(fresh)

        assertFalse(gate.isCurrent(stale!!))
        assertTrue(gate.isCurrent(fresh!!))
        assertEquals(stale + 1, fresh)
    }

    @Test
    fun `close rejects everything afterwards`() {
        val gate = gate(intervalMs = 0L)

        val generation = gate.tryAcquire()
        assertNotNull(generation)
        gate.release()

        gate.close()

        assertTrue(gate.isClosed)
        assertNull(gate.tryAcquire())
        assertFalse(
            "work in flight when the processor closed must not publish",
            gate.isCurrent(generation!!),
        )
    }

    @Test
    fun `close is idempotent`() {
        val gate = gate(intervalMs = 0L)

        gate.close()
        gate.close()

        assertTrue(gate.isClosed)
        assertNull(gate.tryAcquire())
    }

    @Test
    fun `releasing without acquiring is harmless`() {
        val gate = gate(intervalMs = 0L)

        gate.release()
        assertNotNull(gate.tryAcquire())
    }

    /**
     * The failure that matters most: if an error path forgets to release, the
     * pipeline wedges forever. This pins the contract that release recovers it.
     */
    @Test
    fun `an error path that releases keeps the pipeline alive`() {
        val gate = gate(intervalMs = 0L)

        repeat(3) {
            val generation = gate.tryAcquire()
            assertNotNull("iteration $it should be admitted", generation)
            // Simulate OCR failing and the caller releasing on the error path.
            gate.release()
        }

        assertNotNull(gate.tryAcquire())
    }
}
