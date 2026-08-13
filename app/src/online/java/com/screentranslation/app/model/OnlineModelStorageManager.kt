package com.screentranslation.app.model

import android.content.Context
import com.screentranslation.app.ml.onlinePreparationIdentity
import com.screentranslation.app.online.OnlineTranslationConfigRepository

class OnlineModelStorageManager(context: Context) :
    ModelStorageManager {
    private val repository = OnlineTranslationConfigRepository(context.applicationContext)

    override fun scan(): List<ManagedModel> = emptyList()

    override fun deleteDownloadedModels(): Long = 0L

    override fun preparationDescriptor(
        sourceLanguage: String,
        targetLanguage: String,
    ): ModelPreparationDescriptor {
        val ready = repository.requireReady()
        val identity = checkNotNull(
            onlinePreparationIdentity(
                baseUrl = ready.endpoint.baseUrl,
                modelId = ready.modelId,
                consentHost = ready.config.consentHost,
                consentVersion = ready.config.consentVersion,
                apiKey = ready.apiKey,
            ),
        )
        return ModelPreparationDescriptor(
            edition = "online",
            modelIds = listOf(ready.modelId),
            revisions = listOf("consent-v${ready.config.consentVersion}"),
            expectedSha256 = listOf(identity),
            downloadBytes = 0L,
            installedBytes = 0L,
        )
    }
}
