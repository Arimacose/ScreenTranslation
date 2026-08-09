package com.screentranslation.app.model

import android.content.Context
import com.screentranslation.app.ml.HyMt2Q4ModelDescriptor
import java.io.File

class HyMt2ModelStorageManager(context: Context) : ModelStorageManager {
    private val modelsRoot = File(context.noBackupFilesDir, "models")
    private val root = File(context.noBackupFilesDir, HyMt2Q4ModelDescriptor.MODEL_RELATIVE_DIRECTORY)

    override fun scan(): List<ManagedModel> {
        val model = File(root, HyMt2Q4ModelDescriptor.MODEL_FILE_NAME)
        val partial = File(root, "${HyMt2Q4ModelDescriptor.MODEL_FILE_NAME}.part")
        val marker = File(root, "${HyMt2Q4ModelDescriptor.MODEL_FILE_NAME}.sha256")
        val ready = model.isFile &&
            model.length() == HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES &&
            marker.isFile &&
            marker.readText(Charsets.UTF_8).trim()
                .equals(HyMt2Q4ModelDescriptor.MODEL_SHA256, ignoreCase = true)
        return listOf(
            ManagedModel(
                id = "hymt2-q4",
                displayName = "HY-MT2 1.8B Q4_K_M Experimental",
                revision = HyMt2Q4ModelDescriptor.MODEL_REVISION,
                state = when {
                    ready -> ModelDownloadState.READY
                    partial.isFile || model.isFile -> ModelDownloadState.PARTIAL
                    else -> ModelDownloadState.NOT_DOWNLOADED
                },
                downloadedBytes = root.recursiveSizeBytes(),
                expectedBytes = HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES,
            ),
        )
    }

    override fun deleteDownloadedModels(): Long = deleteModelDirectory(modelsRoot, root)
}
