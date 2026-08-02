package com.screentranslation.app.ml

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.atomic.AtomicBoolean

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
    ): TranslationCall {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Translation engine is closed")))
            return TranslationCall.NONE
        }
        if (prepared) {
            onResult(Result.success(Unit))
            return TranslationCall.NONE
        }

        var shouldStartDownload = false
        synchronized(preparationLock) {
            if (closed.get()) {
                onResult(Result.failure(IllegalStateException("Translation engine is closed")))
                return TranslationCall.NONE
            }
            if (prepared) {
                onResult(Result.success(Unit))
                return TranslationCall.NONE
            }

            preparationCallbacks += onResult
            if (!preparing) {
                preparing = true
                shouldStartDownload = true
            }
        }

        if (!shouldStartDownload) return TranslationCall.NONE
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
        return TranslationCall.NONE
    }

    override fun translate(
        text: String,
        onResult: (Result<String>) -> Unit,
    ): TranslationCall {
        if (closed.get()) {
            onResult(Result.failure(IllegalStateException("Translation engine is closed")))
            return TranslationCall.NONE
        }
        if (text.isBlank() || passThrough) {
            onResult(Result.success(text))
            return TranslationCall.NONE
        }
        if (!prepared) {
            prepare { preparation ->
                preparation.fold(
                    onSuccess = { translatePrepared(text, onResult) },
                    onFailure = { onResult(Result.failure(it)) },
                )
            }
            return TranslationCall.NONE
        }

        translatePrepared(text, onResult)
        return TranslationCall.NONE
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
