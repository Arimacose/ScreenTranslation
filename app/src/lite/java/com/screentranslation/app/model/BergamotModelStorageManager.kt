package com.screentranslation.app.model

import android.content.Context
import com.screentranslation.app.ml.BergamotModelSpec
import com.screentranslation.app.ml.BergamotLiteProviderContract
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

    override fun preparationDescriptor(
        sourceLanguage: String,
        targetLanguage: String,
    ): ModelPreparationDescriptor {
        val source = sourceLanguage.trim().lowercase()
        val target = targetLanguage.trim().lowercase()
        val route = BergamotLiteProviderContract.modelIdsByRoute.entries.firstOrNull { entry ->
            entry.key.sourceLanguageTag == source && entry.key.targetLanguageTag == target
        } ?: throw IllegalArgumentException("Unsupported Bergamot route: $source→$target")
        val catalog = bergamotModelSpecs().associateBy(BergamotModelSpec::id)
        val specs = route.value.map(catalog::getValue)
        return ModelPreparationDescriptor(
            edition = "lite",
            modelIds = specs.map(BergamotModelSpec::id),
            revisions = specs.map { spec ->
                spec.baseUrl.removeSuffix("/").removeSuffix("/exported").substringAfterLast('/')
            },
            expectedSha256 = specs.flatMap { spec ->
                spec.files.flatMap { file ->
                    listOf(file.compressedSha256, file.outputSha256)
                }
            },
            downloadBytes = specs.sumOf { spec -> spec.files.sumOf { it.compressedSize } },
            installedBytes = specs.sumOf { spec -> spec.files.sumOf { it.outputSize } },
        )
    }

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
