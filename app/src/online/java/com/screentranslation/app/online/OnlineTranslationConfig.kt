package com.screentranslation.app.online

internal const val MAX_ONLINE_MODEL_ID_LENGTH = 256

data class OnlineTranslationConfig(
    val baseUrl: String = "",
    val modelId: String = "",
    val consentVersion: Int = 0,
    val consentHost: String = "",
) {
    fun cacheIdentity(
        sourceLanguage: String,
        targetLanguage: String,
    ): String = listOf(
        baseUrl,
        modelId,
        sourceLanguage,
        targetLanguage,
    ).joinToString(separator = "\u001f")

    companion object {
        const val CURRENT_CONSENT_VERSION = 1
    }
}

/** Deliberately not a data class: generated toString/copy must not expose the key. */
internal class ReadyOnlineTranslationConfig(
    val config: OnlineTranslationConfig,
    val endpoint: OpenAiEndpoint,
    val apiKey: String,
)
