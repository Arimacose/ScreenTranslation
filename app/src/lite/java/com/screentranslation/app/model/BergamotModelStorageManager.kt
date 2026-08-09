package com.screentranslation.app.model

import android.content.Context
import com.screentranslation.app.ml.BergamotModelSpec
import com.screentranslation.app.ml.bergamotModelSpecs
import com.screentranslation.app.ml.isBergamotModelPreparedAndStable
import com.screentranslation.app.service.ScreenTranslationService
import java.io.File

class BergamotModelStorageManager(context: Context) : ModelStorageManager {
    private val modelsRoot = File(context.noBackupFilesDir, "models")
    private val root = File(modelsRoot, "bergamot-lite")

    override fun scan(): List<ManagedModel> = bergamotModelSpecs().map { spec ->
        checkScanActive()
        val directory = File(root, spec.id)
        val bytes = directory.recursiveSizeBytes()
        val hasPartial = directory.walkTopDown().any { it.isFile && it.name.endsWith(".part") }
        ManagedModel(
            id = spec.id,
            displayName = DISPLAY_NAMES.getValue(spec.id),
            revision = spec.baseUrl.removeSuffix("/").removeSuffix("/exported")
                .substringAfterLast('/'),
            state = resolveBergamotModelDownloadState(
                root = root,
                spec = spec,
                hasPartial = hasPartial,
                checkActive = ::checkScanActive,
            ),
            downloadedBytes = bytes,
            expectedBytes = spec.files.sumOf { it.outputSize },
        )
    }

    override fun deleteDownloadedModels(): Long = deleteModelDirectory(modelsRoot, root)

    private fun checkScanActive() {
        check(!ScreenTranslationService.isRunning) {
            "Bergamot model inventory verification paused while capture is running"
        }
        check(!Thread.currentThread().isInterrupted) {
            "Bergamot model inventory was cancelled"
        }
    }

    private companion object {
        val DISPLAY_NAMES = mapOf(
            "en-zh" to "Bergamot English → Chinese",
            "ja-en" to "Bergamot Japanese → English",
        )
    }
}

internal fun resolveBergamotModelDownloadState(
    root: File,
    spec: BergamotModelSpec,
    hasPartial: Boolean,
    checkActive: () -> Unit = {},
): ModelDownloadState {
    checkActive()
    val ready = isBergamotModelPreparedAndStable(
        root = root,
        model = spec,
        checkOpen = checkActive,
    )
    return when {
        ready -> ModelDownloadState.READY
        hasPartial -> ModelDownloadState.PARTIAL
        else -> ModelDownloadState.NOT_DOWNLOADED
    }
}
