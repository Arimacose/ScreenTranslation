package com.screentranslation.app.model.preparation

import androidx.work.Data
import androidx.work.WorkInfo
import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.ModelPreparationStage

enum class ModelPreparationPhase {
    IDLE,
    QUEUED,
    WAITING_FOR_NETWORK,
    STORAGE_PREFLIGHT,
    PREPARING,
    DOWNLOADING,
    EXTRACTING,
    VERIFYING,
    READY,
    PAUSED,
    CANCELLED,
    FAILED,
}

data class ModelPreparationSnapshot(
    val taskId: String,
    val phase: ModelPreparationPhase,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val message: String? = null,
) {
    val isActive: Boolean
        get() = phase in setOf(
            ModelPreparationPhase.QUEUED,
            ModelPreparationPhase.WAITING_FOR_NETWORK,
            ModelPreparationPhase.STORAGE_PREFLIGHT,
            ModelPreparationPhase.PREPARING,
            ModelPreparationPhase.DOWNLOADING,
            ModelPreparationPhase.EXTRACTING,
            ModelPreparationPhase.VERIFYING,
        )
}

internal data class TransferEstimate(
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
)

internal fun estimateTransfer(
    previousBytes: Long,
    currentBytes: Long,
    elapsedMillis: Long,
    totalBytes: Long,
): TransferEstimate {
    if (elapsedMillis <= 0L || currentBytes <= previousBytes) {
        return TransferEstimate(0L, null)
    }
    val bytesPerSecond = ((currentBytes - previousBytes) * 1_000L / elapsedMillis)
        .coerceAtLeast(1L)
    val remaining = (totalBytes - currentBytes).coerceAtLeast(0L)
    return TransferEstimate(
        bytesPerSecond = bytesPerSecond,
        etaSeconds = if (totalBytes > 0L) (remaining + bytesPerSecond - 1L) / bytesPerSecond else null,
    )
}

internal fun requiredPreparationBytes(
    downloadBytes: Long,
    installedBytes: Long,
    existingBytes: Long,
): Long {
    require(downloadBytes >= 0L && installedBytes >= 0L && existingBytes >= 0L)
    val peakBytes = downloadBytes + installedBytes
    if (peakBytes == 0L) return 0L
    val remaining = (peakBytes - existingBytes).coerceAtLeast(0L)
    return remaining + (remaining / 10L) + MIN_STORAGE_HEADROOM_BYTES
}

internal fun phaseFor(progress: ModelPreparationProgress): ModelPreparationPhase =
    when (progress.stage) {
        ModelPreparationStage.PREPARING -> ModelPreparationPhase.PREPARING
        ModelPreparationStage.DOWNLOADING -> ModelPreparationPhase.DOWNLOADING
        ModelPreparationStage.EXTRACTING -> ModelPreparationPhase.EXTRACTING
        ModelPreparationStage.VERIFYING -> ModelPreparationPhase.VERIFYING
        ModelPreparationStage.LOADING_RUNTIME -> ModelPreparationPhase.PREPARING
    }

internal fun snapshotData(snapshot: ModelPreparationSnapshot): Data = Data.Builder()
    .putString(KEY_TASK_ID, snapshot.taskId)
    .putString(KEY_PHASE, snapshot.phase.name)
    .putLong(KEY_COMPLETED_BYTES, snapshot.completedBytes)
    .putLong(KEY_TOTAL_BYTES, snapshot.totalBytes)
    .putLong(KEY_BYTES_PER_SECOND, snapshot.bytesPerSecond)
    .putLong(KEY_ETA_SECONDS, snapshot.etaSeconds ?: -1L)
    .putString(KEY_MESSAGE, snapshot.message)
    .build()

internal fun snapshotFrom(
    workInfo: WorkInfo,
    taskId: String,
): ModelPreparationSnapshot {
    val data = if (workInfo.state.isFinished) workInfo.outputData else workInfo.progress
    val explicit = data.getString(KEY_PHASE)?.let { name ->
        runCatching { ModelPreparationPhase.valueOf(name) }.getOrNull()
    }
    val phase = explicit ?: when (workInfo.state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        -> ModelPreparationPhase.QUEUED
        WorkInfo.State.RUNNING -> ModelPreparationPhase.PREPARING
        WorkInfo.State.SUCCEEDED -> ModelPreparationPhase.READY
        WorkInfo.State.FAILED -> ModelPreparationPhase.FAILED
        WorkInfo.State.CANCELLED -> ModelPreparationPhase.CANCELLED
    }
    return ModelPreparationSnapshot(
        taskId = data.getString(KEY_TASK_ID) ?: taskId,
        phase = phase,
        completedBytes = data.getLong(KEY_COMPLETED_BYTES, 0L),
        totalBytes = data.getLong(KEY_TOTAL_BYTES, 0L),
        bytesPerSecond = data.getLong(KEY_BYTES_PER_SECOND, 0L),
        etaSeconds = data.getLong(KEY_ETA_SECONDS, -1L).takeIf { it >= 0L },
        message = data.getString(KEY_MESSAGE),
    )
}

internal const val MODEL_PREPARATION_TAG = "screen-translation-model-preparation"
internal const val KEY_TASK_ID = "task_id"
internal const val KEY_SOURCE_LANGUAGE = "source_language"
internal const val KEY_TARGET_LANGUAGE = "target_language"
internal const val KEY_PHASE = "phase"
internal const val KEY_COMPLETED_BYTES = "completed_bytes"
internal const val KEY_TOTAL_BYTES = "total_bytes"
internal const val KEY_BYTES_PER_SECOND = "bytes_per_second"
internal const val KEY_ETA_SECONDS = "eta_seconds"
internal const val KEY_MESSAGE = "message"
internal const val MIN_STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
