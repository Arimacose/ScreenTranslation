package com.screentranslation.app.online

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal class OpenAiEndpoint private constructor(
    val baseUrl: String,
    val requestUrl: HttpUrl,
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
            val endpointText = if (
                normalized.endsWith(CHAT_COMPLETIONS_PATH, ignoreCase = false)
            ) {
                normalized
            } else {
                normalized + CHAT_COMPLETIONS_PATH
            }
            val endpoint = checkNotNull(endpointText.toHttpUrlOrNull())
            val base = if (
                normalized.endsWith(CHAT_COMPLETIONS_PATH, ignoreCase = false)
            ) {
                normalized.removeSuffix(CHAT_COMPLETIONS_PATH).ifBlank {
                    "https://${parsed.host}"
                }
            } else {
                normalized
            }
            return OpenAiEndpoint(
                baseUrl = base,
                requestUrl = endpoint,
                host = endpoint.host.lowercase(Locale.ROOT),
                consentIdentity = "${endpoint.host.lowercase(Locale.ROOT)}:${endpoint.port}",
            )
        }

        private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        private const val MAX_BASE_URL_LENGTH = 2_048
    }
}
