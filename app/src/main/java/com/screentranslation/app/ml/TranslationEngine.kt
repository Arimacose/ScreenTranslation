package com.screentranslation.app.ml

import android.content.Context
import com.screentranslation.app.BuildConfig
import java.lang.reflect.InvocationTargetException

enum class ModelPreparationStage {
    PREPARING,
    DOWNLOADING,
    VERIFYING,
    LOADING_RUNTIME,
}

data class ModelPreparationProgress(
    val stage: ModelPreparationStage,
    val completedBytes: Long? = null,
    val totalBytes: Long? = null,
)

/** Common asynchronous contract shared by ML Kit and experimental backends. */
interface TranslationBackend : AutoCloseable {
    fun prepare(
        requireWifi: Boolean = false,
        warmRuntime: Boolean = true,
        onProgress: (ModelPreparationProgress) -> Unit = {},
        onResult: (Result<Unit>) -> Unit,
    )

    fun translate(text: String, onResult: (Result<String>) -> Unit)
}

/**
 * Keeps edition-specific code out of sibling APKs while preserving a typed
 * backend boundary in the shared capture pipeline.
 */
object TranslationBackendFactory {
    fun create(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBackend {
        val backendClassName = when {
            BuildConfig.HYMT2_Q4_EXPERIMENTAL -> HYMT2_BACKEND_CLASS
            BuildConfig.BERGAMOT_LITE -> BERGAMOT_BACKEND_CLASS
            else -> error("No translation backend is configured for this edition")
        }

        try {
            val backendClass = Class.forName(backendClassName)
            val constructor = backendClass.getConstructor(
                Context::class.java,
                String::class.java,
                String::class.java,
            )
            return constructor.newInstance(
                context.applicationContext,
                sourceLanguage,
                targetLanguage,
            ) as TranslationBackend
        } catch (error: InvocationTargetException) {
            throw error.targetException
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(
                "Selected translation backend is missing from this APK",
                error,
            )
        }
    }

    private const val HYMT2_BACKEND_CLASS =
        "com.screentranslation.app.ml.HyMt2Q4TranslationEngine"
    private const val BERGAMOT_BACKEND_CLASS =
        "com.screentranslation.app.ml.BergamotTranslationEngine"
}
