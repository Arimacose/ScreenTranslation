package com.screentranslation.app.model

import android.content.Context
import com.screentranslation.app.BuildConfig
import java.io.File
import java.lang.reflect.InvocationTargetException

enum class ModelDownloadState {
    NOT_DOWNLOADED,
    PARTIAL,
    READY,
}

data class ManagedModel(
    val id: String,
    val displayName: String,
    val revision: String,
    val state: ModelDownloadState,
    val downloadedBytes: Long,
    val expectedBytes: Long? = null,
)

interface ModelStorageManager {
    fun scan(): List<ManagedModel>

    /** Returns the number of bytes present immediately before deletion. */
    fun deleteDownloadedModels(): Long
}

object ModelStorageManagerFactory {
    fun create(context: Context): ModelStorageManager {
        val className = when {
            BuildConfig.ONLINE_LLM -> ONLINE_MANAGER_CLASS
            BuildConfig.HYMT2_Q4_EXPERIMENTAL -> HYMT2_MANAGER_CLASS
            BuildConfig.BERGAMOT_LITE -> BERGAMOT_MANAGER_CLASS
            else -> error("No model storage manager is configured for this edition")
        }
        try {
            val managerClass = Class.forName(className)
            return managerClass.getConstructor(Context::class.java)
                .newInstance(context.applicationContext) as ModelStorageManager
        } catch (error: InvocationTargetException) {
            throw error.targetException
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Selected model storage manager is missing", error)
        }
    }

    private const val BERGAMOT_MANAGER_CLASS =
        "com.screentranslation.app.model.BergamotModelStorageManager"
    private const val HYMT2_MANAGER_CLASS =
        "com.screentranslation.app.model.HyMt2ModelStorageManager"
    private const val ONLINE_MANAGER_CLASS =
        "com.screentranslation.app.model.OnlineModelStorageManager"
}

internal fun File.recursiveSizeBytes(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    return walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

internal fun deleteModelDirectory(modelsRoot: File, target: File): Long {
    val rootPath = modelsRoot.canonicalFile.toPath()
    val targetPath = target.canonicalFile.toPath()
    require(targetPath.startsWith(rootPath) && targetPath != rootPath) {
        "Model deletion target is outside the application model directory"
    }
    val bytes = target.recursiveSizeBytes()
    if (target.exists()) {
        check(target.deleteRecursively()) { "Failed to delete downloaded model files" }
    }
    return bytes
}
