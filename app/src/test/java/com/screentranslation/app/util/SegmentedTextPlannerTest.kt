package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedTextPlannerTest {
    @Test
    fun `mixed English and Chinese preserves target and version`() {
        val plan = SegmentedTextPlanner.plan("设置 Settings (v2.1.0)", "en", "zh")

        assertEquals(1, plan.translatedSpanCount)
        assertFalse(plan.requestText.contains("设置"))
        assertFalse(plan.requestText.contains("v2.1.0"))
        assertEquals("设置 设置 (v2.1.0)", plan.restore(plan.requestText.replace("Settings", "设置")))
    }

    @Test
    fun `strict target skip retains conservative rollback policy`() {
        val plan = SegmentedTextPlanner.plan(
            "设置 Settings",
            "en",
            "zh",
            SourceRoutingPolicy.STRICT_TARGET_SKIP,
        )

        assertEquals(0, plan.translatedSpanCount)
        assertEquals("", plan.requestText)
    }

    @Test
    fun `Japanese Kana context routes adjacent Kanji`() {
        val plan = SegmentedTextPlanner.plan("設定を確認", "ja", "zh")

        assertTrue(plan.translatedSpanCount >= 1)
        assertEquals("打开设置", plan.restore("打开设置"))
    }

    @Test
    fun `bounded Japanese UI lexicon routes pure Kanji`() {
        val plan = SegmentedTextPlanner.plan("設定", "ja", "zh")

        assertEquals(1, plan.translatedSpanCount)
        assertEquals("设置", plan.restore("设置"))
    }

    @Test
    fun `unknown pure Han remains preserved in smart mode`() {
        val plan = SegmentedTextPlanner.plan("屏幕翻译", "ja", "zh")

        assertEquals(0, plan.translatedSpanCount)
    }

    @Test
    fun `explicit Japanese source routes unknown pure Han`() {
        val plan = SegmentedTextPlanner.plan(
            "屏幕翻译",
            "ja",
            "zh",
            SourceRoutingPolicy.EXPLICIT_SOURCE,
        )

        assertEquals(1, plan.translatedSpanCount)
    }

    @Test
    fun `protected values survive translation unchanged`() {
        val source = "Open https://example.com/file and app-release.apk at 10:30 for ￥1,299.00"
        val plan = SegmentedTextPlanner.plan(source, "en", "zh")
        val translated = plan.requestText
            .replace("Open", "打开")
            .replace("file", "文件")
            .replace("and", "以及")
            .replace("at", "在")
            .replace("for", "价格")

        val restored = plan.restore(translated)
        assertTrue(restored.contains("https://example.com/file"))
        assertTrue(restored.contains("app-release.apk"))
        assertTrue(restored.contains("10:30"))
        assertTrue(restored.contains("￥1,299.00"))
    }

    @Test
    fun `missing duplicate and unexpected tokens are rejected`() {
        val plan = SegmentedTextPlanner.plan("设置 Settings v2.1.0", "en", "zh")
        val token = Regex("⟦SMX_[^⟧]+⟧").find(plan.requestText)!!.value

        assertThrows(IllegalArgumentException::class.java) {
            plan.restore(plan.requestText.replace(token, ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.restore(plan.requestText + token)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.restore(plan.requestText + "⟦SMX_deadbeef_9999⟧")
        }
    }

    @Test(timeout = 2_000)
    fun `adversarial long mixed input remains bounded`() {
        val input = buildString(80_000) {
            repeat(20_000) { append("A设1 ") }
        }
        val plan = SegmentedTextPlanner.plan(input, "en", "zh")

        assertTrue(plan.spans.size <= 4_096)
        assertTrue(plan.translatedSpanCount > 0)
    }
}
