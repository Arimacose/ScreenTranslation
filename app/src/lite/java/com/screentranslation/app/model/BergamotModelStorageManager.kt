package com.screentranslation.app.model

import android.content.Context
import com.screentranslation.app.ml.bergamotModelSpecs
import java.io.File

class BergamotModelStorageManager(context: Context) : ModelStorageManager {
    private val modelsRoot = File(context.noBackupFilesDir, "models")
    private val root = File(modelsRoot, "bergamot-lite")

    override fun scan(): List<ManagedModel> = bergamotModelSpecs().map { spec ->
        val directory = File(root, spec.id)
        val bytes = directory.recursiveSizeBytes()
        val hasPartial = directory.walkTopDown().any { it.isFile && it.name.endsWith(".part") }
        val ready = directory.isDirectory &&
            File(directory, "decoder.yml").isFile && spec.files.all { file ->
                val output = File(directory, file.outputName)
                val marker = File(directory, "${file.outputName}.sha256")
                output.isFile && output.length() == file.outputSize && marker.isFile &&
                    marker.readText(Charsets.UTF_8).trim()
                        .equals(file.outputSha256, ignoreCase = true)
            }
        ManagedModel(
            id = spec.id,
            displayName = DISPLAY_NAMES.getValue(spec.id),
            revision = spec.baseUrl.removeSuffix("/").removeSuffix("/exported")
                .substringAfterLast('/'),
            state = when {
                hasPartial -> ModelDownloadState.PARTIAL
                ready -> ModelDownloadState.READY
                else -> ModelDownloadState.NOT_DOWNLOADED
            },
            downloadedBytes = bytes,
            expectedBytes = spec.files.sumOf { it.outputSize },
        )
    }

    override fun deleteDownloadedModels(): Long = deleteModelDirectory(modelsRoot, root)

    private companion object {
        val DISPLAY_NAMES = mapOf(
            "en-zh" to "Bergamot English → Chinese",
            "ja-en" to "Bergamot Japanese → English",
        )
    }
}
