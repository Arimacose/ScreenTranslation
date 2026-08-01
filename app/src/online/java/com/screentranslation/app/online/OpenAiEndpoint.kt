package com.screentranslation.app.online

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal class OpenAiEndpoint private constructor(
    val baseUrl: String,
    val requestUrl: HttpUrl,
    val modelsUrl: HttpUrl,
    val host: String,
    val consentIdentity: String,
) {
    companion object {
        fun parse(rawBaseUrl: String): OpenAiEndpoint {
            val raw = rawBaseUrl.trim()
            require(raw.isNotEmpty()) { "Base URL is blank" }
            require(raw.length <= MAX_BASE_URL_LENGTH) { "Base URL is too long" }
            val parsed = raw.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Base URL is invalid")
            require(parsed.scheme.lowercase(Locale.ROOT) == "https") {
                "Base URL must use HTTPS"
            }
            require(parsed.host.isNotBlank()) { "Base URL host is missing" }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "Base URL must not contain credentials"
            }
            require(parsed.query == null) { "Base URL must not contain a query" }
            require(parsed.fragment == null) { "Base URL must not contain a fragment" }

            val normalized = parsed.toString().removeSuffix("/")
            val base = when {
                normalized.endsWith(CHAT_COMPLETIONS_PATH, ignoreCase = false) ->
                    normalized.removeSuffix(CHAT_COMPLETIONS_PATH)
                normalized.endsWith(MODELS_PATH, ignoreCase = false) ->
                    normalized.removeSuffix(MODELS_PATH)
                else -> normalized
            }.ifBlank {
                "https://${parsed.host}"
            }
            val endpoint = checkNotNull((base + CHAT_COMPLETIONS_PATH).toHttpUrlOrNull())
            val modelsEndpoint = checkNotNull((base + MODELS_PATH).toHttpUrlOrNull())
            return OpenAiEndpoint(
                baseUrl = base,
                requestUrl = endpoint,
                modelsUrl = modelsEndpoint,
                host = endpoint.host.lowercase(Locale.ROOT),
                consentIdentity = "${endpoint.host.lowercase(Locale.ROOT)}:${endpoint.port}",
            )
        }

        private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        private const val MODELS_PATH = "/models"
        private const val MAX_BASE_URL_LENGTH = 2_048
    }
}
