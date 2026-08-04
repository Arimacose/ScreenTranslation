package com.screentranslation.app.capture

import android.graphics.RectF
import android.media.ImageReader

interface FramePipeline : AutoCloseable {
    fun setEnabled(value: Boolean)

    fun resetStability()

    fun onImageAvailable(
        reader: ImageReader,
        normalizedRegion: RectF = FULL_FRAME,
        normalizedExclusions: List<RectF> = emptyList(),
    )

    companion object {
        val FULL_FRAME = RectF(0f, 0f, 1f, 1f)
    }
}
