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
            providerMode = OnlineProviderMode.fromStoredValue(
                value = preferences.getString(KEY_PROVIDER_MODE, null),
                hasLegacyUserConfig = baseUrl.isNotBlank() || modelId.isNotBlank(),
            ),
            baseUrl = baseUrl,
            modelId = modelId,
            consentVersion = preferences.getInt(KEY_CONSENT_VERSION, 0),
            consentHost = preferences.getString(KEY_CONSENT_HOST, "").orEmpty(),
            managedConsentVersion = preferences.getInt(KEY_MANAGED_CONSENT_VERSION, 0),
            managedConsentHost = preferences.getString(KEY_MANAGED_CONSENT_HOST, "").orEmpty(),
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
            providerMode = OnlineProviderMode.USER_API,
            baseUrl = endpoint.baseUrl,
            modelId = normalizedModel,
            consentVersion = OnlineTranslationConfig.CURRENT_CONSENT_VERSION,
            consentHost = endpoint.consentIdentity,
            managedConsentVersion = previous.managedConsentVersion,
            managedConsentHost = previous.managedConsentHost,
        )
        preferences.edit(commit = true) {
            putString(KEY_PROVIDER_MODE, config.providerMode.storedValue)
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_MODEL_ID, config.modelId)
            putInt(KEY_CONSENT_VERSION, config.consentVersion)
            putString(KEY_CONSENT_HOST, config.consentHost)
        }
        return config
    }

    fun saveManagedCloud(consentAccepted: Boolean): OnlineTranslationConfig {
        val endpoint = ManagedCloudService.endpoint()
        val previous = load()
        val consentValid = consentAccepted || (
            previous.managedConsentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION &&
                previous.managedConsentHost == endpoint.consentIdentity
            )
        require(consentValid) { "Data-flow consent is required for managed cloud" }
        preferences.edit(commit = true) {
            putString(KEY_PROVIDER_MODE, OnlineProviderMode.MANAGED_CLOUD.storedValue)
            putInt(
                KEY_MANAGED_CONSENT_VERSION,
                OnlineTranslationConfig.CURRENT_CONSENT_VERSION,
            )
            putString(KEY_MANAGED_CONSENT_HOST, endpoint.consentIdentity)
        }
        return load()
    }

    fun requireReady(): ReadyOnlineTranslationConfig {
        val config = load()
        if (config.providerMode == OnlineProviderMode.MANAGED_CLOUD) {
            val endpoint = ManagedCloudService.endpoint()
            require(
                config.managedConsentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION &&
                    config.managedConsentHost == endpoint.consentIdentity,
            ) {
                "Data-flow consent is required for managed cloud"
            }
            return ReadyOnlineTranslationConfig(
                config = config,
                endpoint = endpoint,
                modelId = ManagedCloudService.PUBLIC_MODEL_ID,
                apiKey = null,
            )
        }
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
        const val KEY_MANAGED_CONSENT_VERSION = "managed_consent_version"
        const val KEY_MANAGED_CONSENT_HOST = "managed_consent_host"
    }
}
