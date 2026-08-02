package com.screentranslation.app.online

import android.content.Context
import androidx.core.content.edit

internal class OnlineTranslationConfigRepository(
    context: Context,
    private val secretStore: ApiKeySecretStore = AndroidKeystoreSecretCipher(context),
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    fun load(): OnlineTranslationConfig {
        val baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty()
        val modelId = preferences.getString(KEY_MODEL_ID, "").orEmpty()
        return OnlineTranslationConfig(
            baseUrl = baseUrl,
            modelId = modelId,
            consentVersion = preferences.getInt(KEY_CONSENT_VERSION, 0),
            consentHost = preferences.getString(KEY_CONSENT_HOST, "").orEmpty(),
        )
    }

    fun save(
        baseUrl: String,
        modelId: String,
        newApiKey: String?,
        consentAccepted: Boolean,
    ): OnlineTranslationConfig {
        val endpoint = OpenAiEndpoint.parse(baseUrl)
        val normalizedModel = modelId.trim()
        require(normalizedModel.isNotEmpty()) { "Model ID is blank" }
        require(normalizedModel.length <= MAX_ONLINE_MODEL_ID_LENGTH) { "Model ID is too long" }

        val previous = load()
        val hostChanged = previous.consentHost != endpoint.consentIdentity
        val consentValid = consentAccepted || (
            !hostChanged &&
                previous.consentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION
            )
        require(consentValid) { "Data-flow consent is required for this service host" }

        newApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { apiKey ->
            validateApiKey(apiKey)
            secretStore.save(apiKey)
        }
        val config = OnlineTranslationConfig(
            baseUrl = endpoint.baseUrl,
            modelId = normalizedModel,
            consentVersion = OnlineTranslationConfig.CURRENT_CONSENT_VERSION,
            consentHost = endpoint.consentIdentity,
        )
        preferences.edit(commit = true) {
            remove(KEY_PROVIDER_MODE)
            remove(KEY_MANAGED_CONSENT_VERSION)
            remove(KEY_MANAGED_CONSENT_HOST)
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_MODEL_ID, config.modelId)
            putInt(KEY_CONSENT_VERSION, config.consentVersion)
            putString(KEY_CONSENT_HOST, config.consentHost)
        }
        return config
    }

    fun requireReady(): ReadyOnlineTranslationConfig {
        val config = load()
        val endpoint = OpenAiEndpoint.parse(config.baseUrl)
        require(config.modelId.isNotBlank()) { "Model ID is not configured" }
        require(
            config.consentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION &&
                config.consentHost == endpoint.consentIdentity,
        ) {
            "Data-flow consent is required for this service host"
        }
        val apiKey = secretStore.load()
        require(!apiKey.isNullOrBlank()) { "API key is not configured" }
        return ReadyOnlineTranslationConfig(
            config = config,
            endpoint = endpoint,
            modelId = config.modelId,
            apiKey = apiKey,
        )
    }

    fun hasApiKey(): Boolean = secretStore.hasSecret()

    fun resolveApiKey(newApiKey: String?): String {
        val apiKey = newApiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: secretStore.load()?.trim().orEmpty()
        require(apiKey.isNotEmpty()) { "API key is not configured" }
        validateApiKey(apiKey)
        return apiKey
    }

    fun deleteApiKey() {
        secretStore.delete()
    }

    private fun validateApiKey(apiKey: String) {
        require(apiKey.length <= MAX_API_KEY_LENGTH) { "API key is too long" }
        require('\r' !in apiKey && '\n' !in apiKey) {
            "API key contains invalid characters"
        }
    }

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
        const val PREFERENCES_FILE = "online_translation_config"
        const val KEY_PROVIDER_MODE = "provider_mode"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_CONSENT_VERSION = "consent_version"
        const val KEY_CONSENT_HOST = "consent_host"
        // Removed in the BYOK-only Online release. Kept solely for one-time cleanup.
        const val KEY_MANAGED_CONSENT_VERSION = "managed_consent_version"
        const val KEY_MANAGED_CONSENT_HOST = "managed_consent_host"
    }
}
