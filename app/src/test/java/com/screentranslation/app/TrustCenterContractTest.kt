package com.screentranslation.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustCenterContractTest {
    @Test
    fun `offline trust documents are exact copies of repository policy files`() {
        mapOf(
            "APACHE-2.0.txt" to "LICENSE",
            "PRIVACY.md" to "PRIVACY.md",
            "SECURITY.md" to "SECURITY.md",
            "THIRD_PARTY_NOTICES.md" to "THIRD_PARTY_NOTICES.md",
        ).forEach { (assetName, rootName) ->
            assertEquals(
                source("../$rootName").readBytes().toList(),
                source("src/main/assets/trust/$assetName").readBytes().toList(),
            )
        }
    }

    @Test
    fun `about screen presents local data flow without secret values`() {
        val about = source("src/main/java/com/screentranslation/app/AboutActivity.kt").readText()
        assertTrue("BuildConfig.EDITION_ID" in about)
        assertTrue("BuildConfig.OCR_BACKEND_ID" in about)
        assertTrue("BuildConfig.TRANSLATION_BACKEND_ID" in about)
        assertFalse("apiKey" in about)
    }

    private fun source(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app", relativePath))
        return candidates.firstOrNull(File::isFile)
            ?: error("Could not locate source: $relativePath")
    }
}
