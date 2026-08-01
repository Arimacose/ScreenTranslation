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

    fun load(): OnlineTranslationConfig = OnlineTranslationConfig(
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
        modelId = preferences.getString(KEY_MODEL_ID, "").orEmpty(),
        consentVersion = preferences.getInt(KEY_CONSENT_VERSION, 0),
        consentHost = preferences.getString(KEY_CONSENT_HOST, "").orEmpty(),
    )

    fun save(
        baseUrl: String,
        modelId: String,
        newApiKey: String?,
        consentAccepted: Boolean,
    ): OnlineTranslationConfig {
        val endpoint = OpenAiEndpoint.parse(baseUrl)
        val normalizedModel = modelId.trim()
        require(normalizedModel.isNotEmpty()) { "Model ID is blank" }
        require(normalizedModel.length <= MAX_MODEL_ID_LENGTH) { "Model ID is too long" }

        val previous = load()
        val hostChanged = previous.consentHost != endpoint.consentIdentity
        val consentValid = consentAccepted || (
            !hostChanged &&
                previous.consentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION
            )
        require(consentValid) { "Data-flow consent is required for this service host" }

        newApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let(secretStore::save)
        val config = OnlineTranslationConfig(
            baseUrl = endpoint.baseUrl,
            modelId = normalizedModel,
            consentVersion = OnlineTranslationConfig.CURRENT_CONSENT_VERSION,
            consentHost = endpoint.consentIdentity,
        )
        preferences.edit(commit = true) {
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
        return ReadyOnlineTranslationConfig(config, endpoint, apiKey)
    }

    fun hasApiKey(): Boolean = secretStore.hasSecret()

    fun deleteApiKey() {
        secretStore.delete()
    }

    private companion object {
        const val MAX_MODEL_ID_LENGTH = 256
        const val PREFERENCES_FILE = "online_translation_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_CONSENT_VERSION = "consent_version"
        const val KEY_CONSENT_HOST = "consent_host"
    }
}
