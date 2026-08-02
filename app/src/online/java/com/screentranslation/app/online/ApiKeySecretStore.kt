package com.screentranslation.app.online

internal interface ApiKeySecretStore {
    fun save(apiKey: String)
    fun load(): String?
    fun hasSecret(): Boolean
    fun delete()
}
