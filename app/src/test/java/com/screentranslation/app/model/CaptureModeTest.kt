package com.screentranslation.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureModeTest {
    @Test
    fun `region remains the default for missing or unknown preferences`() {
        assertEquals(CaptureMode.REGION, CaptureMode.fromPersisted(null))
        assertEquals(CaptureMode.REGION, CaptureMode.fromPersisted("future_mode"))
    }

    @Test
    fun `full screen experimental mode round trips explicitly`() {
        val mode = CaptureMode.FULL_SCREEN_INCREMENTAL

        assertEquals(mode, CaptureMode.fromPersisted(mode.persistedValue))
    }
}
