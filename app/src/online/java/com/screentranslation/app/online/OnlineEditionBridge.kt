package com.screentranslation.app.online

import android.content.Context
import com.screentranslation.app.R

/** Reflection-safe bridge used by the shared MainActivity. */
object OnlineEditionBridge {
    @JvmStatic
    fun configurationSummary(context: Context): String {
        val repository = OnlineTranslationConfigRepository(context.applicationContext)
        val config = repository.load()
        val host = runCatching { OpenAiEndpoint.parse(config.baseUrl).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.online_unconfigured)
        val model = config.modelId.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.online_unconfigured)
        val keyState = context.getString(
            if (repository.hasApiKey()) {
                R.string.online_key_state_saved
            } else {
                R.string.online_key_state_missing
            },
        )
        return context.getString(
            R.string.online_config_summary,
            host,
            model,
            keyState,
        )
    }
}
