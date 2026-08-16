package com.screentranslation.app.model.preparation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.screentranslation.app.R
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationBackendFactory
import com.screentranslation.app.model.ModelStorageManagerFactory
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ModelPreparationWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : Worker(context, workerParameters) {
    private val store = ModelPreparationStore(applicationContext)

    @Volatile
    private var activeEngine: TranslationBackend? = null

    override fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val source = inputData.getString(KEY_SOURCE_LANGUAGE) ?: return Result.failure()
        val target = inputData.getString(KEY_TARGET_LANGUAGE) ?: return Result.failure()
        val manager = ModelStorageManagerFactory.create(applicationContext)
        val descriptor = runCatching { manager.preparationDescriptor(source, target) }
            .getOrElse { error -> return fail(taskId, error) }
        if (descriptor.taskId != taskId) {
            return fail(taskId, IllegalStateException("Model preparation identity changed"))
        }

        publish(ModelPreparationSnapshot(taskId, ModelPreparationPhase.STORAGE_PREFLIGHT))
        val existingBytes = runCatching { manager.scan().sumOf { it.downloadedBytes } }
            .getOrDefault(0L)
        val requiredBytes = requiredPreparationBytes(
            downloadBytes = descriptor.downloadBytes,
            installedBytes = descriptor.installedBytes,
            existingBytes = existingBytes,
        )
        val availableBytes = applicationContext.noBackupFilesDir.usableSpace
        if (availableBytes < requiredBytes) {
            return fail(
                taskId,
                IllegalStateException(
                    "Insufficient storage: need $requiredBytes bytes, available $availableBytes bytes",
                ),
            )
        }

        val engine = runCatching {
            TranslationBackendFactory.create(applicationContext, source, target)
        }.getOrElse { error -> return fail(taskId, error) }
        activeEngine = engine
        val completion = AtomicReference<kotlin.Result<Unit>>()
        val latch = CountDownLatch(1)
        var lastBytes = 0L
        var lastProgressAt = System.nanoTime()
        try {
            engine.prepare(
                requireWifi = false,
                warmRuntime = false,
                onProgress = { progress ->
                    val now = System.nanoTime()
                    val completed = progress.completedBytes ?: lastBytes
                    val total = progress.totalBytes ?: descriptor.downloadBytes
                    val estimate = estimateTransfer(
                        previousBytes = lastBytes,
                        currentBytes = completed,
                        elapsedMillis = (now - lastProgressAt) / 1_000_000L,
                        totalBytes = total,
                    )
                    if (completed > lastBytes) {
                        lastBytes = completed
                        lastProgressAt = now
                    }
                    publish(
                        ModelPreparationSnapshot(
                            taskId = taskId,
                            phase = phaseFor(progress),
                            completedBytes = completed,
                            totalBytes = total,
                            bytesPerSecond = estimate.bytesPerSecond,
                            etaSeconds = estimate.etaSeconds,
                        ),
                    )
                },
                onResult = { result ->
                    completion.set(result)
                    latch.countDown()
                },
            )
            while (!latch.await(250L, TimeUnit.MILLISECONDS)) {
                if (isStopped) throw InterruptedException("Model preparation stopped")
            }
            completion.get()?.getOrThrow()
                ?: throw IllegalStateException("Model preparation completed without a result")
            val identity = engine.currentPreparationIdentity()
                ?: throw IllegalStateException("Prepared model has no verified identity")
            store.markVerified(taskId, identity)
            val ready = ModelPreparationSnapshot(
                taskId = taskId,
                phase = ModelPreparationPhase.READY,
                completedBytes = descriptor.downloadBytes,
                totalBytes = descriptor.downloadBytes,
            )
            publish(ready)
            return Result.success(snapshotData(ready))
        } catch (error: Throwable) {
            if (isStopped) {
                return Result.failure()
            }
            if (error.hasCause<IOException>()) {
                val waiting = ModelPreparationSnapshot(
                    taskId = taskId,
                    phase = ModelPreparationPhase.WAITING_FOR_NETWORK,
                    completedBytes = lastBytes,
                    totalBytes = descriptor.downloadBytes,
                    message = error.message,
                )
                publish(waiting)
                return Result.retry()
            }
            return fail(taskId, error)
        } finally {
            activeEngine = null
            runCatching { engine.close() }
        }
    }

    override fun onStopped() {
        activeEngine?.let { runCatching { it.close() } }
        activeEngine = null
        val taskId = inputData.getString(KEY_TASK_ID)
        if (taskId != null) {
            val current = store.loadSnapshot(taskId)
            if (current?.phase !in setOf(
                    ModelPreparationPhase.PAUSED,
                    ModelPreparationPhase.CANCELLED,
                )
            ) {
                store.saveSnapshot(
                    (current ?: ModelPreparationSnapshot(taskId, ModelPreparationPhase.IDLE)).copy(
                        phase = if (hasUsableNetwork()) {
                            ModelPreparationPhase.QUEUED
                        } else {
                            ModelPreparationPhase.WAITING_FOR_NETWORK
                        },
                    ),
                )
            }
        }
        super.onStopped()
    }

    private fun publish(snapshot: ModelPreparationSnapshot) {
        store.saveSnapshot(snapshot)
        setForegroundAsync(foregroundInfo(snapshot)).get()
        setProgressAsync(snapshotData(snapshot)).get()
    }

    private fun fail(taskId: String, error: Throwable): Result {
        val snapshot = ModelPreparationSnapshot(
            taskId = taskId,
            phase = ModelPreparationPhase.FAILED,
            message = error.message ?: error.javaClass.simpleName,
        )
        store.clearVerified(taskId)
        store.saveSnapshot(snapshot)
        return Result.failure(snapshotData(snapshot))
    }

    private fun foregroundInfo(snapshot: ModelPreparationSnapshot): ForegroundInfo {
        ensureChannel()
        val source = inputData.getString(KEY_SOURCE_LANGUAGE).orEmpty()
        val target = inputData.getString(KEY_TARGET_LANGUAGE).orEmpty()
        val pauseIntent = ModelPreparationActionReceiver.pendingIntent(
            applicationContext,
            ModelPreparationActionReceiver.ACTION_PAUSE,
            snapshot.taskId,
            source,
            target,
        )
        val cancelIntent = ModelPreparationActionReceiver.pendingIntent(
            applicationContext,
            ModelPreparationActionReceiver.ACTION_CANCEL,
            snapshot.taskId,
            source,
            target,
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.model_worker_title))
            .setContentText(notificationText(snapshot))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(snapshot.isActive)
            .setOnlyAlertOnce(true)
            .setProgress(
                snapshot.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                snapshot.completedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                snapshot.totalBytes <= 0L,
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(applicationContext, R.drawable.ic_notification),
                    applicationContext.getString(R.string.model_pause),
                    pauseIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(applicationContext, R.drawable.ic_notification),
                    applicationContext.getString(R.string.model_cancel),
                    cancelIntent,
                ).build(),
            )
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notificationText(snapshot: ModelPreparationSnapshot): String = buildString {
        append(snapshot.phase.name.lowercase().replace('_', ' '))
        if (snapshot.totalBytes > 0L) {
            val percent = snapshot.completedBytes * 100L / snapshot.totalBytes
            append(" · ").append(percent.coerceIn(0L, 100L)).append('%')
        }
        snapshot.etaSeconds?.let { append(" · ETA ").append(it).append('s') }
    }

    private fun ensureChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.model_worker_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
    }

    private fun hasUsableNetwork(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var cursor: Throwable? = this
        while (cursor != null) {
            if (cursor is T) return true
            cursor = cursor.cause
        }
        return false
    }

    private companion object {
        const val CHANNEL_ID = "screen_translation_model_preparation"
        const val NOTIFICATION_ID = 1206
    }
}
