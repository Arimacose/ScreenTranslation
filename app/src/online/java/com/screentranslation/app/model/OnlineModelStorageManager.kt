package com.screentranslation.app.model

import android.content.Context

class OnlineModelStorageManager(@Suppress("UNUSED_PARAMETER") context: Context) :
    ModelStorageManager {
    override fun scan(): List<ManagedModel> = emptyList()

    override fun deleteDownloadedModels(): Long = 0L
}
