package com.screentranslation.app.model.preparation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.screentranslation.app.ml.TranslationBackendFactory
import com.screentranslation.app.model.ModelPreparationDescriptor
import com.screentranslation.app.model.ModelStorageManagerFactory

class ModelPreparationCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val store = ModelPreparationStore(appContext)

    fun descriptor(sourceLanguage: String, targetLanguage: String): ModelPreparationDescriptor =
        ModelStorageManagerFactory.create(appContext).preparationDescriptor(
            sourceLanguage,
            targetLanguage,
        )

    fun workName(taskId: String): String = "model-preparation-$taskId"

    fun observeAll(): LiveData<List<WorkInfo>> =
        workManager.getWorkInfosByTagLiveData(MODEL_PREPARATION_TAG)

    fun snapshotFor(
        workInfos: List<WorkInfo>?,
        taskId: String,
    ): ModelPreparationSnapshot? {
        val tag = taskTag(taskId)
        val stored = store.loadSnapshot(taskId)
        val matching = workInfos.orEmpty().filter { tag in it.tags }
        if (stored?.phase in setOf(ModelPreparationPhase.PAUSED, ModelPreparationPhase.CANCELLED)) {
            return stored
        }
        val active = matching.lastOrNull { info ->
            info.state in setOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED,
                WorkInfo.State.RUNNING,
            )
        }
        if (active != null) {
            if (
                active.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) &&
                stored?.phase == ModelPreparationPhase.WAITING_FOR_NETWORK
            ) {
                return stored
            }
            return snapshotFrom(active, taskId)
        }
        return matching.lastOrNull()?.let { snapshotFrom(it, taskId) } ?: stored
    }

    fun enqueue(
        sourceLanguage: String,
        targetLanguage: String,
        requireUnmeteredNetwork: Boolean,
        replace: Boolean = false,
    ): String {
        val descriptor = descriptor(sourceLanguage, targetLanguage)
        val taskId = descriptor.taskId
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (requireUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<ModelPreparationWorker>()
            .setConstraints(constraints)
            .setInputData(
                Data.Builder()
                    .putString(KEY_TASK_ID, taskId)
                    .putString(KEY_SOURCE_LANGUAGE, sourceLanguage)
                    .putString(KEY_TARGET_LANGUAGE, targetLanguage)
                    .build(),
            )
            .addTag(MODEL_PREPARATION_TAG)
            .addTag(taskTag(taskId))
            .build()
        val queued = ModelPreparationSnapshot(
            taskId,
            if (hasRequiredNetwork(requireUnmeteredNetwork)) {
                ModelPreparationPhase.QUEUED
            } else {
                ModelPreparationPhase.WAITING_FOR_NETWORK
            },
        )
        store.saveSnapshot(queued)
        workManager.enqueueUniqueWork(
            workName(taskId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        return taskId
    }

    fun pause(taskId: String) {
        store.saveSnapshot(
            (store.loadSnapshot(taskId) ?: ModelPreparationSnapshot(taskId, ModelPreparationPhase.IDLE))
                .copy(phase = ModelPreparationPhase.PAUSED),
        )
        workManager.cancelUniqueWork(workName(taskId))
    }

    fun cancel(taskId: String) {
        store.saveSnapshot(
            (store.loadSnapshot(taskId) ?: ModelPreparationSnapshot(taskId, ModelPreparationPhase.IDLE))
                .copy(phase = ModelPreparationPhase.CANCELLED),
        )
        store.clearVerified(taskId)
        workManager.cancelUniqueWork(workName(taskId))
    }

    fun isReady(
        sourceLanguage: String,
        targetLanguage: String,
    ): Boolean = verifiedCurrentIdentity(sourceLanguage, targetLanguage) != null

    fun verifiedCurrentIdentity(
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        val taskId = runCatching { descriptor(sourceLanguage, targetLanguage).taskId }
            .getOrNull() ?: return null
        val verifiedIdentity = store.verifiedIdentity(taskId) ?: return null
        val engine = runCatching {
            TranslationBackendFactory.create(appContext, sourceLanguage, targetLanguage)
        }.getOrNull() ?: return null
        return try {
            runCatching { engine.currentPreparationIdentity() }.getOrNull()
                ?.takeIf { currentIdentity -> currentIdentity == verifiedIdentity }
        } finally {
            runCatching { engine.close() }
        }
    }

    private fun taskTag(taskId: String): String = "model-preparation-task-$taskId"

    private fun hasRequiredNetwork(requireUnmeteredNetwork: Boolean): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return connected && (
            !requireUnmeteredNetwork ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            )
    }
}
