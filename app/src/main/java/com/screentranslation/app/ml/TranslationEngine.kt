package com.screentranslation.app.ml

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.screentranslation.app.BuildConfig
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

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
 * Keeps experimental code out of production variants while preserving a typed
 * backend boundary in the shared capture pipeline.
 */
object TranslationBackendFactory {
    fun create(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBackend {
        if (!BuildConfig.HYMT2_Q4_EXPERIMENTAL) {
            return TranslationEngine(sourceLanguage, targetLanguage)
        }

        try {
            val backendClass = Class.forName(HYMT2_BACKEND_CLASS)
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
                "Hy-MT2 Q4 experimental backend is missing from this APK",
                error,
            )
        }
    }

    private const val HYMT2_BACKEND_CLASS =
        "com.screentranslation.app.ml.HyMt2Q4TranslationEngine"
}

/**
 * On-device ML Kit translator with explicit model preparation.
 *
 * [prepare] is idempotent and coalesces concurrent callers into one model
 * download. [translate] also prepares lazily, so a service restart remains
 * robust when the UI did not pre-warm the model.
 */
class TranslationEngine(
    sourceLanguage: String,
    targetLanguage: String,
) : TranslationBackend {
    private val sourceLanguageCode = requireSupportedLanguage(sourceLanguage, "source")
    private val targetLanguageCode = requireSupportedLanguage(targetLanguage, "target")
    private val passThrough = sourceLanguageCode == targetLanguageCode
    private val translator: Translator? = if (passThrough) {
        null
    } else {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguageCode)
                .setTargetLanguage(targetLanguageCode)
                .build(),
        )
    }

    private val closed = AtomicBoolean(false)
    private val preparationLock = Any()
    private val preparationCallbacks = mutableListOf<(Result<Unit>) -> Unit>()

    @Volatile
    private var prepared = passThrough

    @Volatile
    private var preparing = false

    override fun prepare(
        requireWifi: Boolean,
        warmRuntime: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Translation engine is closed")))
            return
        }
        if (prepared) {
            onResult(Result.success(Unit))
            return
        }

        var shouldStartDownload = false
        synchronized(preparationLock) {
            if (closed.get()) {
                onResult(Result.failure(IllegalStateException("Translation engine is closed")))
                return
            }
            if (prepared) {
                onResult(Result.success(Unit))
                return
            }

            preparationCallbacks += onResult
            if (!preparing) {
                preparing = true
                shouldStartDownload = true
            }
        }

        if (!shouldStartDownload) return
        onProgress(ModelPreparationProgress(ModelPreparationStage.PREPARING))

        val conditionsBuilder = DownloadConditions.Builder()
        if (requireWifi) {
            conditionsBuilder.requireWifi()
        }

        checkNotNull(translator)
            .downloadModelIfNeeded(conditionsBuilder.build())
            .addOnCompleteListener { task ->
                val result = if (task.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        task.exception
                            ?: IllegalStateException("Model download failed without an exception"),
                    )
                }

                val callbacks: List<(Result<Unit>) -> Unit>
                synchronized(preparationLock) {
                    prepared = task.isSuccessful && !closed.get()
                    preparing = false
                    callbacks = preparationCallbacks.toList()
                    preparationCallbacks.clear()
                }
                callbacks.forEach { it(result) }
            }
    }

    override fun translate(text: String, onResult: (Result<String>) -> Unit) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Translation engine is closed")))
            return
        }
        if (text.isBlank() || passThrough) {
            onResult(Result.success(text))
            return
        }
        if (!prepared) {
            prepare { preparation ->
                preparation.fold(
                    onSuccess = { translatePrepared(text, onResult) },
                    onFailure = { onResult(Result.failure(it)) },
                )
            }
            return
        }

        translatePrepared(text, onResult)
    }

    private fun translatePrepared(text: String, onResult: (Result<String>) -> Unit) {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Translation engine is closed")))
            return
        }

        checkNotNull(translator)
            .translate(text)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(Result.success(task.result.orEmpty()))
                } else {
                    onResult(
                        Result.failure(
                            task.exception
                                ?: IllegalStateException("Translation failed without an exception"),
                        ),
                    )
                }
            }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val callbacks: List<(Result<Unit>) -> Unit>
        synchronized(preparationLock) {
            callbacks = preparationCallbacks.toList()
            preparationCallbacks.clear()
            preparing = false
            prepared = false
        }
        val error = IllegalStateException("Translation engine is closed")
        callbacks.forEach { it(Result.failure(error)) }
        translator?.close()
    }

    private companion object {
        fun requireSupportedLanguage(languageTag: String, role: String): String =
            requireNotNull(TranslateLanguage.fromLanguageTag(languageTag.trim())) {
                "Unsupported $role translation language: $languageTag"
            }
    }
}
