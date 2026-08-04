package com.screentranslation.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturePrivacyInstrumentedTest {
    @Test
    fun appPrivateStorageContainsNoScreenshotOrOcrHistoryArtifacts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roots = listOf(
            context.filesDir,
            context.cacheDir,
            context.noBackupFilesDir,
            context.codeCacheDir,
        ).distinctBy(File::getAbsolutePath)
        val forbidden = roots
            .asSequence()
            .filter(File::exists)
            .flatMap { root -> root.walkTopDown().asSequence() }
            .filter(File::isFile)
            .filter(::isCaptureHistoryArtifact)
            .map(File::getAbsolutePath)
            .toList()

        assertTrue(
            "Capture/OCR history artifacts were left in app storage: $forbidden",
            forbidden.isEmpty(),
        )
    }

    private fun isCaptureHistoryArtifact(file: File): Boolean {
        val name = file.name.lowercase()
        val extension = file.extension.lowercase()
        return extension in IMAGE_EXTENSIONS || HISTORY_TOKENS.any(name::contains)
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("bmp", "gif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
        val HISTORY_TOKENS = setOf("capture_history", "ocr_history", "screenshot")
    }
}
