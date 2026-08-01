package com.screentranslation.app.online

import android.content.Context
import com.screentranslation.app.R

/** Reflection-safe bridge used by the shared MainActivity. */
object OnlineEditionBridge {
    @JvmStatic
    fun configurationSummary(context: Context): String {
        val repository = OnlineTranslationConfigRepository(context.applicationContext)
        val config = repository.load()
        val managedMode = config.providerMode == OnlineProviderMode.MANAGED_CLOUD
        val host = runCatching {
            if (managedMode) {
                ManagedCloudService.endpoint().host
            } else {
                OpenAiEndpoint.parse(config.baseUrl).host
            }
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.online_unconfigured)
        val model = if (managedMode) {
            ManagedCloudService.DISPLAY_MODEL_NAME
        } else {
            config.modelId.takeIf { it.isNotBlank() }
        }
            ?: context.getString(R.string.online_unconfigured)
        val keyState = context.getString(
            if (managedMode) {
                R.string.online_key_not_required
            } else if (repository.hasApiKey()) {
                R.string.online_key_state_saved
            } else {
                R.string.online_key_state_missing
            },
        )
        return context.getString(
            R.string.online_config_summary,
            context.getString(
                if (managedMode) {
                    R.string.online_mode_managed
                } else {
                    R.string.online_mode_user_api
                },
            ),
            host,
            model,
            keyState,
        )
    }
}
