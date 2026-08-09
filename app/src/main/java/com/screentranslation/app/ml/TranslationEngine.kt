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

enum class TranslationInputMode {
    CLAUSE_PLAN,
    WHOLE_REGION,
}

fun interface TranslationCall {
    fun cancel()

    companion object {
        val NONE = TranslationCall {}
    }
}

/** Common asynchronous contract shared by local and online backends. */
interface TranslationBackend : AutoCloseable {
    val profile: TranslationProviderProfile

    val inputMode: TranslationInputMode
        get() = profile.input.mode

    /** Separates in-memory cache entries when endpoint/model settings change. */
    val cacheIdentity: String
        get() = javaClass.name

    fun prepare(
        requireWifi: Boolean = false,
        warmRuntime: Boolean = true,
        onProgress: (ModelPreparationProgress) -> Unit = {},
        onResult: (Result<Unit>) -> Unit,
    ): TranslationCall

    fun translate(
        text: String,
        onResult: (Result<String>) -> Unit,
    ): TranslationCall
}

/**
 * Keeps edition-specific code out of sibling APKs while preserving a typed
 * backend boundary in the shared capture pipeline.
 */
object TranslationBackendFactory {
    val profile: TranslationProviderProfile
        get() {
            val configured = buildList {
                if (BuildConfig.ONLINE_LLM) add(TranslationProviderProfiles.onlineByok)
                if (BuildConfig.HYMT2_Q4_EXPERIMENTAL) {
                    add(TranslationProviderProfiles.hyMt2Q4Full)
                }
                if (BuildConfig.BERGAMOT_LITE) add(TranslationProviderProfiles.bergamotLite)
            }
            check(configured.size == 1) {
                "Exactly one translation provider must be configured for this edition"
            }
            return configured.single()
        }

    fun create(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBackend {
        val selectedProfile = profile
        val backendClassName = when (selectedProfile.id) {
            TranslationProviderId.ONLINE_BYOK -> ONLINE_BACKEND_CLASS
            TranslationProviderId.HY_MT2_Q4_FULL -> HYMT2_BACKEND_CLASS
            TranslationProviderId.BERGAMOT_LITE -> BERGAMOT_BACKEND_CLASS
            else -> error("No translation backend class exists for ${selectedProfile.id}")
        }

        try {
            val backendClass = Class.forName(backendClassName)
            val constructor = backendClass.getConstructor(
                Context::class.java,
                String::class.java,
                String::class.java,
            )
            val backend = constructor.newInstance(
                context.applicationContext,
                sourceLanguage,
                targetLanguage,
            ) as TranslationBackend
            check(backend.profile.id == selectedProfile.id) {
                "Translation backend/profile mismatch: selected=${selectedProfile.id}, " +
                    "actual=${backend.profile.id}"
            }
            return backend
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
    private const val ONLINE_BACKEND_CLASS =
        "com.screentranslation.app.ml.OnlineLlmTranslationEngine"
}
