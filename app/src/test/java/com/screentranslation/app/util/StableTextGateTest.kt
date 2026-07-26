package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableTextGateTest {

    @Test
    fun `emits only after required consecutive matches`() {
        val gate = StableTextGate(requiredConsecutiveMatches = 2)

        assertNull(gate.offer("Hello world"))
        assertEquals("Hello world", gate.offer("Hello world"))
    }

    @Test
    fun `does not emit the same stable text repeatedly`() {
        val gate = StableTextGate(requiredConsecutiveMatches = 2)

        assertNull(gate.offer("Hello world"))
        assertEquals("Hello world", gate.offer("Hello world"))
        assertNull(gate.offer("Hello world"))
        assertNull(gate.offer("Hello world"))
    }

    @Test
    fun `minor OCR jitter still counts as a match`() {
        val gate = StableTextGate(
            requiredConsecutiveMatches = 2,
            similarityThreshold = 0.85,
        )

        assertNull(gate.offer("Translate this sentence"))
        assertEquals(
            "Translate this sentenee",
            gate.offer("Translate this sentenee"),
        )
    }

    @Test
    fun `meaningful new text can emit after becoming stable`() {
        val gate = StableTextGate(requiredConsecutiveMatches = 2)

        assertNull(gate.offer("First sentence"))
        assertEquals("First sentence", gate.offer("First sentence"))
        assertNull(gate.offer("Second sentence"))
        assertEquals("Second sentence", gate.offer("Second sentence"))
    }

    @Test
    fun `small but persistent text change emits independently`() {
        val gate = StableTextGate(
            requiredConsecutiveMatches = 2,
            similarityThreshold = 0.90,
        )
        val open = "LIVE SCREEN UPDATE TEST The door is open."
        val closed = "LIVE SCREEN UPDATE TEST The door is closed."

        assertNull(gate.offer(open))
        assertEquals(open, gate.offer(open))
        assertNull(gate.offer(closed))
        assertEquals(closed, gate.offer(closed))
    }

    @Test
    fun `one frame OCR jitter after emission does not emit`() {
        val gate = StableTextGate(requiredConsecutiveMatches = 2)

        assertNull(gate.offer("Verification code 2048"))
        assertEquals("Verification code 2048", gate.offer("Verification code 2048"))
        assertNull(gate.offer("Verification code 204B"))
        assertNull(gate.offer("Verification code 2048"))
    }

    @Test
    fun `normalizes whitespace before comparing and emitting`() {
        val gate = StableTextGate(requiredConsecutiveMatches = 2)

        assertNull(gate.offer("  two\n lines  "))
        assertEquals("two lines", gate.offer("two   lines"))
    }

    @Test
    fun `short or empty samples clear the current candidate`() {
        val gate = StableTextGate(
            requiredConsecutiveMatches = 2,
            minimumTextLength = 2,
        )

        assertNull(gate.offer("candidate"))
        assertNull(gate.offer(" "))
        assertNull(gate.offer("candidate"))
        assertEquals("candidate", gate.offer("candidate"))
    }

    @Test
    fun `reset allows the prior text to emit again`() {
        val gate = StableTextGate(requiredConsecutiveMatches = 1)

        assertEquals("stable", gate.offer("stable"))
        assertNull(gate.offer("stable"))

        gate.reset()

        assertEquals("stable", gate.offer("stable"))
    }
}
