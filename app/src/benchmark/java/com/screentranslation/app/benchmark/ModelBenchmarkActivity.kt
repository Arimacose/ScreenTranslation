package com.screentranslation.app.benchmark

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.screentranslation.app.R
import com.screentranslation.app.ml.MlKitOcrEngine
import com.screentranslation.app.ml.OcrEngine
import com.screentranslation.app.ml.PpOcrv6Engine
import com.screentranslation.app.ml.TranslationEngine
import com.screentranslation.app.util.ClauseSplitter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Debug-only, deterministic model benchmark.
 *
 * The benchmark build installs beside the production app and writes its JSON
 * plus rendered fixtures to external app storage for collection through ADB.
 * Keeping this outside the release source set avoids adding benchmark UI or
 * exported components to production packages.
 */
class ModelBenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setText(R.string.model_benchmark_running)
                textSize = 20f
                setPadding(48, 96, 48, 48)
            },
        )

        Thread(
            {
                val outputDirectory = checkNotNull(getExternalFilesDir(null))
                    .resolve(OUTPUT_DIRECTORY)
                    .apply { mkdirs() }
                outputDirectory.resolve(DONE_FILE).delete()
                outputDirectory.resolve(ERROR_FILE).delete()

                runCatching {
                    runBenchmark(outputDirectory)
                }.onSuccess {
                    outputDirectory.resolve(DONE_FILE).writeText("ok\n")
                    Log.i(
                        TAG,
                        "Benchmark complete: " +
                            "${outputDirectory.resolve(BASELINE_RESULT_FILE)}, " +
                            outputDirectory.resolve(PP_OCR_RESULT_FILE),
                    )
                }.onFailure { error ->
                    outputDirectory.resolve(ERROR_FILE).writeText(
                        error.stackTraceToString(),
                    )
                    Log.e(TAG, "Benchmark failed", error)
                }

                runOnUiThread { finish() }
            },
            "model-benchmark",
        ).start()
    }

    private fun runBenchmark(outputDirectory: File) {
        val translationEngine = if (
            intent.getBooleanExtra(EXTRA_INCLUDE_TRANSLATION, false)
        ) {
            TranslationEngine(SOURCE_LANGUAGE, TARGET_LANGUAGE)
        } else {
            null
        }
        try {
            translationEngine?.let(::awaitPreparation)

            MlKitOcrEngine(SOURCE_LANGUAGE).use { ocrEngine ->
                runSuite(
                    outputDirectory = outputDirectory,
                    ocrEngine = ocrEngine,
                    translationEngine = translationEngine,
                    ocrLabel = "ML Kit Text Recognition Latin 16.0.1",
                    resultFile = BASELINE_RESULT_FILE,
                )
            }
            PpOcrv6Engine(applicationContext).use { ocrEngine ->
                runSuite(
                    outputDirectory = outputDirectory,
                    ocrEngine = ocrEngine,
                    translationEngine = translationEngine,
                    ocrLabel = PpOcrv6Engine.ENGINE_LABEL,
                    resultFile = PP_OCR_RESULT_FILE,
                )
            }
        } finally {
            translationEngine?.close()
        }
    }

    private fun runSuite(
        outputDirectory: File,
        ocrEngine: OcrEngine,
        translationEngine: TranslationEngine?,
        ocrLabel: String,
        resultFile: String,
    ) {
        val warmupBitmap = render(FIXTURES.first())
        awaitRecognition(ocrEngine, warmupBitmap)
        warmupBitmap.recycle()
        translationEngine?.let {
            awaitTranslation(it, "Warm-up sentence.")
        }

        val cases = JSONArray()
        FIXTURES.forEach { fixture ->
            cases.put(
                benchmarkFixture(
                    fixture = fixture,
                    outputDirectory = outputDirectory,
                    ocrEngine = ocrEngine,
                    translationEngine = translationEngine,
                ),
            )
        }

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val result = JSONObject()
            .put("schema_version", 1)
            .put("generated_at", Instant.now().toString())
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("display_build", Build.DISPLAY),
            )
            .put(
                "app",
                JSONObject()
                    .put("package", packageName)
                    .put("version_name", packageInfo.versionName)
                    .put("version_code", packageInfo.longVersionCode),
            )
            .put(
                "engines",
                JSONObject()
                    .put("ocr", ocrLabel)
                    .put(
                        "translation",
                        if (translationEngine == null) {
                            "not run (OCR-only mode)"
                        } else {
                            "ML Kit Translate 17.0.3"
                        },
                    )
                    .put("source_language", SOURCE_LANGUAGE)
                    .put("target_language", TARGET_LANGUAGE),
            )
            .put(
                "method",
                JSONObject()
                    .put("ocr_repetitions", REPETITIONS)
                    .put("latency_clock", "SystemClock.elapsedRealtimeNanos")
                    .put("ocr_only", translationEngine == null),
            )
            .put("cases", cases)

        if (translationEngine != null) {
            result.getJSONObject("method")
                .put("translation_repetitions", REPETITIONS)
                .put("translation_raw", "whole gold source sentence")
                .put(
                    "translation_pipeline",
                    "gold source sentence through current ClauseSplitter",
                )
                .put(
                    "end_to_end",
                    "rendered bitmap -> OcrEngine blocks -> ClauseSplitter -> TranslationEngine",
                )
        }

        outputDirectory.resolve(resultFile).writeText(
            result.toString(2),
            Charsets.UTF_8,
        )
    }

    private fun benchmarkFixture(
        fixture: Fixture,
        outputDirectory: File,
        ocrEngine: OcrEngine,
        translationEngine: TranslationEngine?,
    ): JSONObject {
        val bitmap = render(fixture)
        val imageFile = outputDirectory.resolve("${fixture.id}.png")
        FileOutputStream(imageFile).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }

        val ocrLatencies = mutableListOf<Double>()
        var recognition = OcrEngine.Recognition("", emptyList())
        repeat(REPETITIONS) {
            val measurement = awaitRecognition(ocrEngine, bitmap)
            recognition = measurement.value
            ocrLatencies += measurement.latencyMs
        }

        val result = JSONObject()
            .put("id", fixture.id)
            .put("source_text", fixture.source)
            .put("reference_translation", fixture.referenceTranslation)
            .put("translation_scored", fixture.translationScored)
            .put(
                "render",
                JSONObject()
                    .put("image_file", imageFile.name)
                    .put("width_px", fixture.widthPx)
                    .put("text_size_px", fixture.textSizePx)
                    .put("background_argb", colorHex(fixture.backgroundColor))
                    .put("text_argb", colorHex(fixture.textColor))
                    .put("monospace", fixture.monospace),
            )
            .put(
                "ocr",
                JSONObject()
                    .put("output_text", recognition.text)
                    .put("blocks", JSONArray(recognition.blocks))
                    .put("latencies_ms", JSONArray(ocrLatencies))
                    .put("median_latency_ms", median(ocrLatencies)),
            )

        if (translationEngine == null) {
            bitmap.recycle()
            return result
        }

        val rawLatencies = mutableListOf<Double>()
        var rawTranslation = ""
        repeat(REPETITIONS) {
            val measurement = awaitTranslation(translationEngine, fixture.source)
            rawTranslation = measurement.value
            rawLatencies += measurement.latencyMs
        }

        val pipelineLatencies = mutableListOf<Double>()
        var pipeline = PlanMeasurement("", emptyList(), emptyList(), 0.0)
        repeat(REPETITIONS) {
            pipeline = awaitPlan(
                translationEngine,
                ClauseSplitter.plan(listOf(fixture.source)),
            )
            pipelineLatencies += pipeline.latencyMs
        }

        val endToEndStarted = SystemClock.elapsedRealtimeNanos()
        val endToEndRecognition = awaitRecognition(ocrEngine, bitmap).value
        val endToEndPlan = awaitPlan(
            translationEngine,
            ClauseSplitter.plan(
                endToEndRecognition.blocks.ifEmpty {
                    listOf(endToEndRecognition.text)
                },
            ),
        )
        val endToEndLatencyMs = elapsedMilliseconds(endToEndStarted)

        bitmap.recycle()

        return result
            .put(
                "translation_raw",
                JSONObject()
                    .put("output_text", rawTranslation)
                    .put("latencies_ms", JSONArray(rawLatencies))
                    .put("median_latency_ms", median(rawLatencies)),
            )
            .put(
                "translation_pipeline",
                JSONObject()
                    .put("parts", JSONArray(pipeline.parts))
                    .put("part_outputs", JSONArray(pipeline.partOutputs))
                    .put("output_text", pipeline.output)
                    .put("latencies_ms", JSONArray(pipelineLatencies))
                    .put("median_latency_ms", median(pipelineLatencies)),
            )
            .put(
                "end_to_end",
                JSONObject()
                    .put("ocr_text", endToEndRecognition.text)
                    .put("ocr_blocks", JSONArray(endToEndRecognition.blocks))
                    .put("translation_parts", JSONArray(endToEndPlan.parts))
                    .put("output_text", endToEndPlan.output)
                    .put("latency_ms", endToEndLatencyMs),
            )
    }

    private fun awaitPreparation(engine: TranslationEngine) {
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        engine.prepare(requireWifi = false) {
            result.set(it)
            completed.countDown()
        }
        check(completed.await(MODEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out preparing translation model"
        }
        checkNotNull(result.get()).getOrThrow()
    }

    private fun awaitRecognition(
        engine: OcrEngine,
        bitmap: Bitmap,
    ): Measurement<OcrEngine.Recognition> {
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<OcrEngine.Recognition>>()
        val started = SystemClock.elapsedRealtimeNanos()
        engine.recognize(bitmap) {
            result.set(it)
            completed.countDown()
        }
        check(completed.await(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting for OCR"
        }
        return Measurement(
            value = checkNotNull(result.get()).getOrThrow(),
            latencyMs = elapsedMilliseconds(started),
        )
    }

    private fun awaitTranslation(
        engine: TranslationEngine,
        text: String,
    ): Measurement<String> {
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<String>>()
        val started = SystemClock.elapsedRealtimeNanos()
        engine.translate(text) {
            result.set(it)
            completed.countDown()
        }
        check(completed.await(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting for translation"
        }
        return Measurement(
            value = checkNotNull(result.get()).getOrThrow(),
            latencyMs = elapsedMilliseconds(started),
        )
    }

    private fun awaitPlan(
        engine: TranslationEngine,
        plan: ClauseSplitter.Plan,
    ): PlanMeasurement {
        val parts = plan.parts
        val outputs = arrayOfNulls<String>(parts.size)
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(parts.size)
        val started = SystemClock.elapsedRealtimeNanos()

        parts.forEachIndexed { index, part ->
            engine.translate(part) { result ->
                result.fold(
                    onSuccess = { outputs[index] = it },
                    onFailure = { error.compareAndSet(null, it) },
                )
                completed.countDown()
            }
        }

        check(completed.await(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting for split translation"
        }
        error.get()?.let { throw it }
        val translatedParts = outputs.map { checkNotNull(it) }
        return PlanMeasurement(
            output = plan.reassemble(translatedParts),
            parts = parts,
            partOutputs = translatedParts,
            latencyMs = elapsedMilliseconds(started),
        )
    }

    private fun render(fixture: Fixture): Bitmap {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = fixture.textColor
            textSize = fixture.textSizePx
            typeface = if (fixture.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        }
        val contentWidth = fixture.widthPx - (PADDING_PX * 2)
        val layout = StaticLayout.Builder
            .obtain(fixture.source, 0, fixture.source.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(LINE_SPACING_EXTRA_PX, LINE_SPACING_MULTIPLIER)
            .build()
        val height = max(MINIMUM_HEIGHT_PX, layout.height + (PADDING_PX * 2))
        val bitmap = createBitmap(fixture.widthPx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(fixture.backgroundColor)
        canvas.withTranslation(PADDING_PX.toFloat(), PADDING_PX.toFloat()) {
            layout.draw(this)
        }
        return bitmap
    }

    private fun elapsedMilliseconds(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun colorHex(color: Int): String =
        String.format("#%08X", color)

    private data class Measurement<T>(
        val value: T,
        val latencyMs: Double,
    )

    private data class PlanMeasurement(
        val output: String,
        val parts: List<String>,
        val partOutputs: List<String>,
        val latencyMs: Double,
    )

    private data class Fixture(
        val id: String,
        val source: String,
        val referenceTranslation: String,
        val translationScored: Boolean = true,
        val widthPx: Int = 1080,
        val textSizePx: Float = 42f,
        val backgroundColor: Int = Color.WHITE,
        val textColor: Int = Color.rgb(24, 24, 27),
        val monospace: Boolean = false,
    )

    private companion object {
        const val TAG = "ModelBenchmark"
        const val OUTPUT_DIRECTORY = "model-benchmark"
        const val BASELINE_RESULT_FILE = "baseline-mlkit.json"
        const val PP_OCR_RESULT_FILE = "candidate-ppocrv6-small-android.json"
        const val DONE_FILE = "baseline-mlkit.done"
        const val ERROR_FILE = "baseline-mlkit-error.txt"
        const val EXTRA_INCLUDE_TRANSLATION = "include_translation"
        const val SOURCE_LANGUAGE = "en"
        const val TARGET_LANGUAGE = "zh"
        const val REPETITIONS = 3
        const val MODEL_TIMEOUT_SECONDS = 300L
        const val INFERENCE_TIMEOUT_SECONDS = 60L
        const val PADDING_PX = 64
        const val MINIMUM_HEIGHT_PX = 420
        const val LINE_SPACING_EXTRA_PX = 10f
        const val LINE_SPACING_MULTIPLIER = 1.08f

        val FIXTURES = listOf(
            Fixture(
                id = "issue18_long_compound",
                source = "The translation engine runs entirely on your device, which means " +
                    "the text captured from the screen never leaves the phone and the model " +
                    "keeps working even when there is no network connection available at all.",
                referenceTranslation = "翻译引擎完全在你的设备上运行，这意味着从屏幕捕获的文本绝不会离开手机，" +
                    "而且即使完全没有网络连接，模型也会继续工作。",
            ),
            Fixture(
                id = "literary_long_sentence",
                source = "It is a truth universally acknowledged, that a single man in " +
                    "possession of a good fortune, must be in want of a wife.",
                referenceTranslation = "凡是有钱的单身汉，总想娶位太太，这已经成了一条举世公认的真理。",
                textSizePx = 38f,
            ),
            Fixture(
                id = "notification_recovery",
                source = "Although the overlay was briefly hidden by a notification, the " +
                    "capture service remained alive, and it resumed translating the selected " +
                    "region as soon as the foreground content became stable again.",
                referenceTranslation = "尽管浮窗曾被通知短暂遮挡，捕获服务仍保持运行，并在前台内容重新稳定后立即恢复翻译所选区域。",
                backgroundColor = Color.rgb(26, 28, 32),
                textColor = Color.rgb(238, 242, 247),
            ),
            Fixture(
                id = "numbers_and_symbols",
                source = "Order XT-2048 ships on 2026-07-31 at 09:45; total: " +
                    "£1,249.50 (tax included).",
                referenceTranslation = "订单 XT-2048 将于 2026-07-31 09:45 发货；总计 1,249.50 英镑（含税）。",
                monospace = true,
            ),
            Fixture(
                id = "offline_status",
                source = "Network status: OFFLINE. Worker 10/10 is still running; retry in 1.5 s.",
                referenceTranslation = "网络状态：离线。工作进程 10/10 仍在运行；1.5 秒后重试。",
                textSizePx = 34f,
                backgroundColor = Color.rgb(10, 37, 64),
                textColor = Color.rgb(125, 211, 252),
                monospace = true,
            ),
            Fixture(
                id = "low_contrast_confusables",
                source = "Small low-contrast text should remain readable without turning " +
                    "\"minimum\" into \"minirnum\".",
                referenceTranslation = "小号低对比度文本仍应清晰可读，且不应把“minimum”识别成“minirnum”。",
                textSizePx = 27f,
                backgroundColor = Color.rgb(242, 242, 242),
                textColor = Color.rgb(148, 148, 148),
            ),
            Fixture(
                id = "version_amount_date",
                source = "Version v0.1.0 — build 37; amount: ¥12,345.67; date: 2026-07-31.",
                referenceTranslation = "版本 v0.1.0——构建号 37；金额：¥12,345.67；日期：2026-07-31。",
                textSizePx = 32f,
                monospace = true,
            ),
        )
    }
}
