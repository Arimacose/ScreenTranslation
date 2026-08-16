package com.screentranslation.app.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrv6RuntimeInstrumentedTest {
    @Test
    fun x86_64RuntimeLoadsBothOnnxSessionsAndCompletesInference() {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val engine = PpOcrv6Engine(
            ApplicationProvider.getApplicationContext(),
        )
        val completion = CountDownLatch(1)
        val result = AtomicReference<Result<OcrEngine.Recognition>>()
        try {
            engine.recognize(bitmap) { recognition ->
                result.set(recognition)
                completion.countDown()
            }
            assertTrue("PP-OCRv6 inference timed out", completion.await(90, TimeUnit.SECONDS))
            assertNotNull(result.get()?.getOrThrow())
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }
}
