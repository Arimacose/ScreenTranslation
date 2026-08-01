package com.screentranslation.app.online

internal const val MAX_ONLINE_MODEL_ID_LENGTH = 256

enum class OnlineProviderMode(val storedValue: String) {
    MANAGED_CLOUD("managed_cloud"),
    USER_API("user_api"),
    ;

    companion object {
        fun fromStoredValue(value: String?, hasLegacyUserConfig: Boolean): OnlineProviderMode =
            entries.firstOrNull { it.storedValue == value }
                ?: if (hasLegacyUserConfig) USER_API else MANAGED_CLOUD
    }
}

data class OnlineTranslationConfig(
    val providerMode: OnlineProviderMode = OnlineProviderMode.MANAGED_CLOUD,
    val baseUrl: String = "",
    val modelId: String = "",
    val consentVersion: Int = 0,
    val consentHost: String = "",
    val managedConsentVersion: Int = 0,
    val managedConsentHost: String = "",
) {
    fun cacheIdentity(
        sourceLanguage: String,
        targetLanguage: String,
    ): String = listOf(
        providerMode.storedValue,
        if (providerMode == OnlineProviderMode.MANAGED_CLOUD) {
            ManagedCloudService.configuredBaseUrl
        } else {
            baseUrl
        },
        if (providerMode == OnlineProviderMode.MANAGED_CLOUD) {
            ManagedCloudService.PUBLIC_MODEL_ID
        } else {
            modelId
        },
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
    val modelId: String,
    val apiKey: String?,
)
