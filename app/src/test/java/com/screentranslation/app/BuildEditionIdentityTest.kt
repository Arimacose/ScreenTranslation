package com.screentranslation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildEditionIdentityTest {
    @Test
    fun `each variant exposes exactly one coherent translation edition`() {
        val selected = listOf(
            BuildConfig.BERGAMOT_LITE,
            BuildConfig.HYMT2_Q4_EXPERIMENTAL,
            BuildConfig.ONLINE_LLM,
        ).count { it }
        assertEquals(1, selected)
        assertEquals("ppocrv6-small-onnx", BuildConfig.OCR_BACKEND_ID)

        val expectedBackend = when (BuildConfig.EDITION_ID) {
            "lite" -> {
                assertTrue(BuildConfig.BERGAMOT_LITE)
                "bergamot-lite"
            }
            "full" -> {
                assertTrue(BuildConfig.HYMT2_Q4_EXPERIMENTAL)
                "hymt2-q4"
            }
            "online" -> {
                assertTrue(BuildConfig.ONLINE_LLM)
                "online-byok"
            }
            else -> error("Unknown edition: ${BuildConfig.EDITION_ID}")
        }
        assertEquals(expectedBackend, BuildConfig.TRANSLATION_BACKEND_ID)
    }
}
