package com.screentranslation.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPreparationButtonStateTest {
    @Test
    fun `prepared selected pair is shown ready and cannot be clicked`() {
        val state = resolveModelPreparationButtonState(
            serviceRunning = false,
            operationIdle = true,
            sameLanguage = false,
            readyForSelectedPair = true,
        )

        assertTrue(state.isReady)
        assertFalse(state.isEnabled)
    }

    @Test
    fun `changed pair restores the preparation action`() {
        val state = resolveModelPreparationButtonState(
            serviceRunning = false,
            operationIdle = true,
            sameLanguage = false,
            readyForSelectedPair = false,
        )

        assertFalse(state.isReady)
        assertTrue(state.isEnabled)
    }

    @Test
    fun `preparation stays disabled while another operation is active`() {
        val state = resolveModelPreparationButtonState(
            serviceRunning = false,
            operationIdle = false,
            sameLanguage = false,
            readyForSelectedPair = false,
        )

        assertFalse(state.isReady)
        assertFalse(state.isEnabled)
    }
}
