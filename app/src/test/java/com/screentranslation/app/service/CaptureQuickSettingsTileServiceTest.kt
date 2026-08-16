package com.screentranslation.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureQuickSettingsTileServiceTest {
    @Test
    fun `tile distinguishes not-ready ready running and paused`() {
        assertEquals(
            CaptureTileState.NOT_READY,
            resolveCaptureTileState(false, CaptureLifecyclePhase.STOPPED, false),
        )
        assertEquals(
            CaptureTileState.READY,
            resolveCaptureTileState(false, CaptureLifecyclePhase.STOPPED, true),
        )
        assertEquals(
            CaptureTileState.RUNNING,
            resolveCaptureTileState(true, CaptureLifecyclePhase.RUNNING, true),
        )
        listOf(
            CaptureLifecyclePhase.PAUSED_SCREEN_OFF,
            CaptureLifecyclePhase.PAUSED_CONTENT_HIDDEN,
            CaptureLifecyclePhase.PAUSED_RESULTS_EXPANDED,
            CaptureLifecyclePhase.WAITING_FOR_REGION,
        ).forEach { phase ->
            assertEquals(
                CaptureTileState.PAUSED,
                resolveCaptureTileState(true, phase, false),
            )
        }
    }
}
