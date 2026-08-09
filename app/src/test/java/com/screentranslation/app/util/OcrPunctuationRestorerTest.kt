package com.screentranslation.app.util

import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPunctuationRestorerTest {
    @Test
    fun `restores sentence endings for English Chinese and Japanese`() {
        assertEquals(
            "The app will start translating after the model is ready.",
            OcrPunctuationRestorer.restore(
                "The app will start translating after the model is ready",
                "en-US",
            ),
        )
        assertEquals(
            "模型准备完成之后会自动开始处理下一帧。",
            OcrPunctuationRestorer.restore(
                "模型准备完成之后会自动开始处理下一帧",
                "zh-CN",
            ),
        )
        assertEquals(
            "モデルの準備が完了したら翻訳を開始します。",
            OcrPunctuationRestorer.restore(
                "モデルの準備が完了したら翻訳を開始します",
                "ja-JP",
            ),
        )
    }

    @Test
    fun `closes unambiguous paired punctuation at the block boundary`() {
        assertEquals(
            "“The bridge remains closed while engineers inspect it.”",
            OcrPunctuationRestorer.restore(
                "“The bridge remains closed while engineers inspect it",
                "en",
            ),
        )
        assertEquals(
            "请阅读《屏幕翻译项目长期维护和发布指南》。",
            OcrPunctuationRestorer.restore(
                "请阅读《屏幕翻译项目长期维护和发布指南",
                "zh",
            ),
        )
        assertEquals(
            "「準備が完了したら翻訳を開始します。」",
            OcrPunctuationRestorer.restore(
                "「準備が完了したら翻訳を開始します",
                "ja",
            ),
        )
    }

    @Test
    fun `leaves labels code and ambiguous crossed pairs alone`() {
        assertEquals(
            "Privacy and security",
            OcrPunctuationRestorer.restore("Privacy and security", "en"),
        )
        assertEquals("隐私和安全", OcrPunctuationRestorer.restore("隐私和安全", "zh"))
        assertEquals(
            "プライバシー設定",
            OcrPunctuationRestorer.restore("プライバシー設定", "ja"),
        )
        listOf(
            "Notifications and control center" to "en",
            "Model download and storage settings" to "en",
            "通知和控制中心" to "zh",
            "模型下载和存储设置" to "zh",
            "ネットワークとインターネット" to "ja",
            "モデルのダウンロード設定" to "ja",
        ).forEach { (label, language) ->
            assertEquals(label, OcrPunctuationRestorer.restore(label, language))
        }
        assertEquals("BUILD_TARGET=arm64", OcrPunctuationRestorer.restore("BUILD_TARGET=arm64", "en"))

        val crossed = OcrPunctuationRestorer.restore(
            "The renderer keeps this ] malformed pair ( exactly as OCR reported it",
            "en",
        )
        assertFalse("ambiguous input must not gain a guessed closer: $crossed", crossed.contains(")"))
    }

    @Test
    fun `protected values remain byte identical`() {
        val values = listOf(
            "3.14159",
            "v2.0.0-rc.1",
            "2026-08-09",
            "https://example.com/releases/2.0?channel=stable",
            "demo@example.com",
            "￥1,299.00",
        )
        val raw = "Install ${values[1]} on ${values[2]} from ${values[3]}, contact ${values[4]}, " +
            "and pay ${values[5]} after measuring ${values[0]} units"
        val restored = OcrPunctuationRestorer.restore(raw, "en")

        values.forEach { value -> assertByteIdenticalSubstring(value, restored) }
        assertEquals("$raw.", restored)
    }

    @Test
    fun `restoration is deterministic`() {
        val raw = "The first observer recorded 3.14 units before midnight\n" +
            "The second observer confirmed version v2.0.0 after sunrise"
        val expected = OcrPunctuationRestorer.restore(raw, "en")

        repeat(100) {
            assertEquals(expected, OcrPunctuationRestorer.restore(raw, "en"))
        }
    }

    @Test
    fun `clause splitter treats restored Latin endings as boundaries`() {
        val raw = "The storm crossed the northern valley before midnight while every station " +
            "reported lower water levels\nThe rescue team reopened the mountain road after " +
            "engineers inspected every bridge and radio relay"
        val restored = OcrPunctuationRestorer.restore(raw, "en").normalizeForSplitter()

        assertEquals(1, ClauseSplitter.split(raw.normalizeForSplitter()).size)
        assertEquals(2, ClauseSplitter.split(restored).size)
    }

    @Test
    fun `clause splitter never cuts decimals dates versions urls or abbreviations`() {
        val text = "The measured value stayed at 3.14 throughout the first prolonged inspection " +
            "where Dr. Lee recorded the result on 2026-08-09. The complete report for version v2.0.0 " +
            "remains available at https://example.com/releases/2.0 for every reviewer."

        val parts = ClauseSplitter.split(text)

        assertEquals(2, parts.size)
        listOf("3.14", "Dr. Lee", "2026-08-09", "v2.0.0", "https://example.com/releases/2.0")
            .forEach { protectedValue ->
                assertTrue(
                    "protected value was split or changed: $protectedValue in $parts",
                    parts.any { it.contains(protectedValue) },
                )
            }
    }

    @Test
    fun `fixture benchmark exceeds published quality thresholds`() {
        val fixtures = loadFixtures()
        assertEquals(setOf("en", "zh", "ja"), fixtures.map { it.language }.toSet())

        var exactMatches = 0
        var protectedValues = 0
        var retainedProtectedValues = 0
        var expectedBoundaries = 0
        var baselineBoundaries = 0
        var restoredBoundaries = 0

        fixtures.forEach { fixture ->
            val restored = OcrPunctuationRestorer.restore(fixture.raw, fixture.language)
            if (restored == fixture.expected) {
                exactMatches += 1
            } else {
                println(
                    "PUNCTUATION_MISMATCH id=${fixture.id} " +
                        "expected=${fixture.expected.replace("\n", "\\n")} " +
                        "actual=${restored.replace("\n", "\\n")}",
                )
            }

            fixture.protectedValues.forEach { value ->
                protectedValues += 1
                if (restored.contains(value)) retainedProtectedValues += 1
                assertByteIdenticalSubstring(value, restored)
            }

            val expectedClauseCount = ClauseSplitter.split(
                fixture.expected.normalizeForSplitter(),
            ).size
            assertEquals(
                "fixture expected_clauses drifted: ${fixture.id}",
                fixture.expectedClauses,
                expectedClauseCount,
            )

            if (fixture.category == "boundary") {
                val expected = fixture.expectedClauses - 1
                val baseline = ClauseSplitter.split(fixture.raw.normalizeForSplitter()).size - 1
                val recovered = ClauseSplitter.split(restored.normalizeForSplitter()).size - 1
                expectedBoundaries += expected
                baselineBoundaries += min(expected, baseline)
                restoredBoundaries += min(expected, recovered)
            }
        }

        val falsePositiveFixtures = fixtures.filter { it.category == "false-positive" }
        val preservedFalsePositives = falsePositiveFixtures.count { fixture ->
            OcrPunctuationRestorer.restore(fixture.raw, fixture.language) == fixture.raw
        }
        val exactRate = exactMatches.toDouble() / fixtures.size
        val protectedRetention = retainedProtectedValues.toDouble() / protectedValues
        val falsePositivePreservation =
            preservedFalsePositives.toDouble() / falsePositiveFixtures.size
        val baselineBoundaryRecall = baselineBoundaries.toDouble() / expectedBoundaries
        val restoredBoundaryRecall = restoredBoundaries.toDouble() / expectedBoundaries
        val boundaryGain = restoredBoundaryRecall - baselineBoundaryRecall

        println(
            "PUNCTUATION_QUALITY fixtures=${fixtures.size} " +
                "exact=${exactRate.metric()} protected=${protectedRetention.metric()} " +
                "false_positive=${falsePositivePreservation.metric()} " +
                "baseline_boundary_recall=${baselineBoundaryRecall.metric()} " +
                "restored_boundary_recall=${restoredBoundaryRecall.metric()} " +
                "boundary_gain=${boundaryGain.metric()}",
        )

        assertTrue("exact=$exactRate", exactRate >= EXACT_MATCH_THRESHOLD)
        assertTrue(
            "protected retention=$protectedRetention",
            protectedRetention >= PROTECTED_RETENTION_THRESHOLD,
        )
        assertTrue(
            "false-positive preservation=$falsePositivePreservation",
            falsePositivePreservation >= FALSE_POSITIVE_PRESERVATION_THRESHOLD,
        )
        assertTrue(
            "restored boundary recall=$restoredBoundaryRecall",
            restoredBoundaryRecall >= BOUNDARY_RECALL_THRESHOLD,
        )
        assertTrue("boundary gain=$boundaryGain", boundaryGain >= BOUNDARY_GAIN_THRESHOLD)
    }

    private fun loadFixtures(): List<Fixture> {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "punctuation/punctuation_quality.tsv",
            ),
        )
        return stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.drop(1).filter(String::isNotBlank).map { line ->
                val fields = line.split('\t', limit = 7)
                require(fields.size == 7) { "Expected 7 TSV fields: $line" }
                Fixture(
                    id = fields[0],
                    language = fields[1],
                    category = fields[2],
                    raw = unescape(fields[3]),
                    expected = unescape(fields[4]),
                    expectedClauses = fields[5].toInt(),
                    protectedValues = fields[6].takeUnless { it == "-" }
                        ?.split('|')
                        .orEmpty(),
                )
            }.toList()
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    '\\' -> append('\\')
                    else -> {
                        append('\\')
                        append(value[index + 1])
                    }
                }
                index += 2
            } else {
                append(value[index])
                index += 1
            }
        }
    }

    private fun assertByteIdenticalSubstring(value: String, output: String) {
        val at = output.indexOf(value)
        assertTrue("missing protected value '$value' in '$output'", at >= 0)
        val actual = output.substring(at, at + value.length)
        assertArrayEquals(
            value.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun String.normalizeForSplitter(): String = trim().replace(Regex("\\s+"), " ")

    private fun Double.metric(): String = String.format(Locale.ROOT, "%.3f", this)

    private data class Fixture(
        val id: String,
        val language: String,
        val category: String,
        val raw: String,
        val expected: String,
        val expectedClauses: Int,
        val protectedValues: List<String>,
    )

    private companion object {
        const val EXACT_MATCH_THRESHOLD = 0.95
        const val PROTECTED_RETENTION_THRESHOLD = 1.0
        const val FALSE_POSITIVE_PRESERVATION_THRESHOLD = 1.0
        const val BOUNDARY_RECALL_THRESHOLD = 0.90
        const val BOUNDARY_GAIN_THRESHOLD = 0.50
    }
}
