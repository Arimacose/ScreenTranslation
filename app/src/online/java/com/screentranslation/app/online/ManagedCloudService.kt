package com.screentranslation.app.online

import com.screentranslation.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Public app-facing contract for the project-operated Hy-MT2 service. */
internal object ManagedCloudService {
    const val PUBLIC_MODEL_ID = "hymt2-1.8b-q4"
    const val DISPLAY_MODEL_NAME = "Hy-MT2 1.8B Q4_K_M"

    val configuredBaseUrl: String
        get() = BuildConfig.MANAGED_CLOUD_BASE_URL.trim()

    val isConfigured: Boolean
        get() = configuredBaseUrl.isNotEmpty()

    fun endpoint(): OpenAiEndpoint {
        require(isConfigured) {
            "Managed cloud endpoint is not configured in this build"
        }
        return OpenAiEndpoint.parse(configuredBaseUrl)
    }

    fun requireSupportedTarget(targetLanguage: String) {
        require(targetLanguage.trim().lowercase(Locale.ROOT) in CHINESE_TARGETS) {
            "Managed Hy-MT2 currently translates to Simplified Chinese only"
        }
    }

    private val CHINESE_TARGETS = setOf("zh", "zh-cn", "zh-hans")
}

internal object ManagedHyMt2ChatProtocol {
    fun buildRequestJson(
        sourceLanguage: String,
        targetLanguage: String,
        ocrText: String,
    ): String {
        require(sourceLanguage.isNotBlank()) { "Source language is blank" }
        ManagedCloudService.requireSupportedTarget(targetLanguage)
        val prompt =
            "Translate the following text into Chinese. " +
                "Note that you should only output the translated result without any " +
                "additional explanation:\n\n" + ocrText
        return JSONObject()
            .put("model", ManagedCloudService.PUBLIC_MODEL_ID)
            .put("stream", false)
            .put("temperature", 0)
            .put("top_k", 1)
            .put("top_p", 1)
            .put("repeat_penalty", 1.05)
            .put("seed", 42)
            .put("max_tokens", 256)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt),
                ),
            )
            .toString()
    }
}
