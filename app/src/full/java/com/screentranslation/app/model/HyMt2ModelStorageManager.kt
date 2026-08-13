package com.screentranslation.app.model

import android.content.Context
import com.screentranslation.app.ml.HyMt2Q4ModelDescriptor
import com.screentranslation.app.ml.HyMt2Q4ModelVerifier
import com.screentranslation.app.service.ScreenTranslationService
import java.io.File

class HyMt2ModelStorageManager(context: Context) : ModelStorageManager {
    private val modelsRoot = File(context.noBackupFilesDir, "models")
    private val root = File(context.noBackupFilesDir, HyMt2Q4ModelDescriptor.MODEL_RELATIVE_DIRECTORY)

    override fun scan(): List<ManagedModel> {
        val model = File(root, HyMt2Q4ModelDescriptor.MODEL_FILE_NAME)
        val partial = File(root, "${HyMt2Q4ModelDescriptor.MODEL_FILE_NAME}.part")
        val marker = File(root, "${HyMt2Q4ModelDescriptor.MODEL_FILE_NAME}.sha256")
        val verification = HyMt2Q4ModelVerifier.verify(
            modelFile = model,
            markerFile = marker,
            expectedSize = HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES,
            expectedSha256 = HyMt2Q4ModelDescriptor.MODEL_SHA256,
            checkActive = {
                check(!ScreenTranslationService.isRunning) {
                    "Hy-MT2 Q4 inventory verification paused while capture is running"
                }
                check(!Thread.currentThread().isInterrupted) {
                    "Hy-MT2 Q4 inventory verification was cancelled"
                }
            },
        )
        return listOf(
            ManagedModel(
                id = "hymt2-q4",
                displayName = "HY-MT2 1.8B Q4_K_M Experimental",
                revision = HyMt2Q4ModelDescriptor.MODEL_REVISION,
                state = when {
                    verification.ready -> ModelDownloadState.READY
                    partial.isFile || model.isFile -> ModelDownloadState.PARTIAL
                    else -> ModelDownloadState.NOT_DOWNLOADED
                },
                downloadedBytes = root.recursiveSizeBytes(),
                expectedBytes = HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES,
            ),
        )
    }

    override fun deleteDownloadedModels(): Long = deleteModelDirectory(modelsRoot, root)

    override fun preparationDescriptor(
        sourceLanguage: String,
        targetLanguage: String,
    ): ModelPreparationDescriptor = ModelPreparationDescriptor(
        edition = "full",
        modelIds = listOf("hymt2-q4"),
        revisions = listOf(HyMt2Q4ModelDescriptor.MODEL_REVISION),
        expectedSha256 = listOf(HyMt2Q4ModelDescriptor.MODEL_SHA256),
        downloadBytes = HyMt2Q4ModelDescriptor.MODEL_SIZE_BYTES,
        // The verified .part file is atomically renamed to the final GGUF; no
        // second extracted copy exists at peak storage usage.
        installedBytes = 0L,
    )
}
