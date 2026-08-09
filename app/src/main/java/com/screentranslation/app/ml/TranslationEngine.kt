package com.screentranslation.app.ml

import android.content.Context
import com.screentranslation.app.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

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

    /**
     * Performs a read-only check of the already stored model/configuration.
     *
     * Local implementations must validate the pinned bytes rather than trust
     * an Activity-lifetime flag. Callers run this potentially expensive check
     * off the main thread. Online implementations validate the complete saved
     * BYOK configuration without issuing a translation request.
     */
    fun isPrepared(): Boolean = false

    /**
     * Cheap, process-local identity for a previously verified preparation.
     * This is used only across configuration changes; it never replaces the
     * complete [isPrepared] verification performed after a cold start.
     */
    fun currentPreparationIdentity(): String? = null

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

internal fun stablePreparationIdentity(parts: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * A backend that just completed a full integrity check may cache that exact
 * identity for TOCTOU protection, but every caller must still observe the
 * current artifact. Deletion or replacement after the hash therefore yields
 * null instead of publishing stale readiness.
 */
internal fun resolveCurrentPreparationIdentity(
    lastVerifiedIdentity: String?,
    freshlyObservedIdentity: String?,
): String? = if (lastVerifiedIdentity == null) {
    freshlyObservedIdentity
} else {
    freshlyObservedIdentity?.takeIf { it == lastVerifiedIdentity }
}

/**
 * Samples file content and metadata to invalidate a retained configuration-
 * change cache after app-managed deletion, replacement, truncation or ordinary
 * updates. This fast identity assumes the application-private model directory
 * is not being adversarially edited with metadata restored; it is deliberately
 * not an integrity proof. Cold-start recovery and service preparation use the
 * edition's complete SHA-256 verifier.
 */
internal fun File.preparationFileIdentity(expectedSize: Long): String? {
    if (!isFile || length() != expectedSize) return null
    return runCatching {
        val attributes = Files.readAttributes(toPath(), BasicFileAttributes::class.java)
        val sampleDigest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(this, "r").use { input ->
            val sampleSize = 4 * 1024
            val offsets = linkedSetOf(
                0L,
                ((expectedSize - sampleSize).coerceAtLeast(0L) / 2L),
                (expectedSize - sampleSize).coerceAtLeast(0L),
            )
            val buffer = ByteArray(sampleSize)
            offsets.forEach { offset ->
                input.seek(offset)
                val count = input.read(buffer)
                if (count > 0) sampleDigest.update(buffer, 0, count)
            }
        }
        check(isFile && length() == expectedSize) {
            "Preparation artifact changed while its identity was sampled"
        }
        stablePreparationIdentity(
            listOf(
                canonicalPath,
                expectedSize.toString(),
                attributes.creationTime().toString(),
                attributes.lastModifiedTime().toString(),
                attributes.fileKey()?.toString().orEmpty(),
                sampleDigest.digest().joinToString("") { byte -> "%02x".format(byte) },
            ),
        )
    }.getOrNull()
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
            return configured.single().also { selected ->
                check(selected.isSelectable) {
                    "Configured translation provider is blocked by its capability/evaluation gate: " +
                        selected.id
                }
            }
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
            requireSelectedProfileSingleton(selectedProfile, backend.profile)
            check(backend.profile.isSelectable) {
                "Translation backend returned a provider whose admission is not satisfied: " +
                    backend.profile.id
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

/** Prevents an ID-matching copy from bypassing the selected singleton's admission record. */
internal fun requireSelectedProfileSingleton(
    selectedProfile: TranslationProviderProfile,
    backendProfile: TranslationProviderProfile,
) {
    check(backendProfile === selectedProfile) {
        "Translation backend/profile singleton mismatch: selected=${selectedProfile.id}, " +
            "actual=${backendProfile.id}"
    }
}
