package com.screentranslation.app.model.preparation

import android.content.Context
import androidx.core.content.edit

internal class ModelPreparationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    fun saveSnapshot(snapshot: ModelPreparationSnapshot) {
        preferences.edit(commit = true) {
            putString(KEY_CURRENT_TASK, snapshot.taskId)
            putString("phase_${snapshot.taskId}", snapshot.phase.name)
            putLong("completed_${snapshot.taskId}", snapshot.completedBytes)
            putLong("total_${snapshot.taskId}", snapshot.totalBytes)
            putLong("speed_${snapshot.taskId}", snapshot.bytesPerSecond)
            putLong("eta_${snapshot.taskId}", snapshot.etaSeconds ?: -1L)
            putString("message_${snapshot.taskId}", snapshot.message)
        }
    }

    fun loadSnapshot(taskId: String): ModelPreparationSnapshot? {
        val phaseName = preferences.getString("phase_$taskId", null) ?: return null
        val phase = runCatching { ModelPreparationPhase.valueOf(phaseName) }.getOrNull()
            ?: return null
        return ModelPreparationSnapshot(
            taskId = taskId,
            phase = phase,
            completedBytes = preferences.getLong("completed_$taskId", 0L),
            totalBytes = preferences.getLong("total_$taskId", 0L),
            bytesPerSecond = preferences.getLong("speed_$taskId", 0L),
            etaSeconds = preferences.getLong("eta_$taskId", -1L).takeIf { it >= 0L },
            message = preferences.getString("message_$taskId", null),
        )
    }

    fun markVerified(taskId: String, preparationIdentity: String) {
        preferences.edit(commit = true) {
            putString("verified_$taskId", preparationIdentity)
        }
    }

    fun verifiedIdentity(taskId: String): String? =
        preferences.getString("verified_$taskId", null)

    fun clearVerified(taskId: String) {
        preferences.edit(commit = true) { remove("verified_$taskId") }
    }

    private companion object {
        const val FILE_NAME = "model_preparation_state_v1"
        const val KEY_CURRENT_TASK = "current_task"
    }
}
