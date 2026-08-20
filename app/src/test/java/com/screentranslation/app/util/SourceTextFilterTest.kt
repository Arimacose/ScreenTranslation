package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceTextFilterTest {
    @Test
    fun `english source keeps an ordinary english sentence`() {
        assertEquals(
            "The old house stood silent beneath the winter sky.",
            SourceTextFilter.filter(
                "The old house stood silent beneath the winter sky.",
                sourceLanguageTag = "en",
                targetLanguageTag = "zh",
            ),
        )
    }

    @Test
    fun `english to chinese drops chinese only UI text`() {
        assertNull(
            SourceTextFilter.filter(
                "悬浮窗权限：已授予",
                sourceLanguageTag = "en",
                targetLanguageTag = "zh-CN",
            ),
        )
    }

    @Test
    fun `english to chinese admits a mixed line with an eligible latin span`() {
        assertEquals(
            "设置 Settings (v0.4.0)",
            SourceTextFilter.filter(
                "设置 Settings (v0.4.0)",
                sourceLanguageTag = "en-US",
                targetLanguageTag = "zh",
            ),
        )
    }

    @Test
    fun `chinese UI sentence with latin product names is admitted for span routing`() {
        assertEquals(
            "HyperOS / MIUI 会主动回收后台前台服务",
            SourceTextFilter.filter(
                "HyperOS / MIUI 会主动回收后台前台服务",
                sourceLanguageTag = "en",
                targetLanguageTag = "zh",
            ),
        )
    }

    @Test
    fun `protected values do not independently trigger translation`() {
        listOf(
            "12:48",
            "￥1,299.00",
            "2026-08-04",
            "v0.4.0",
            "https://example.com/docs",
            "example.com/docs",
            "hello@example.com",
        ).forEach { value ->
            assertNull(
                "Expected protected-only value to be skipped: $value",
                SourceTextFilter.filter(value, "en", "zh"),
            )
        }
    }

    @Test
    fun `protected values stay intact inside a real english sentence`() {
        val text = "Open https://example.com/docs for version v0.4.0 on 2026-08-04."

        assertEquals(text, SourceTextFilter.filter(text, "en", "zh"))
    }

    @Test
    fun `japanese to chinese accepts kana context and bounded pure Kanji UI lexicon`() {
        assertEquals(
            "これは設定です",
            SourceTextFilter.filter("これは設定です", "ja", "zh"),
        )
        assertEquals("設定", SourceTextFilter.filter("設定", "ja", "zh"))
    }

    @Test
    fun `korean and russian use their selected scripts`() {
        assertEquals("설정을 엽니다", SourceTextFilter.filter("설정을 엽니다", "ko", "zh"))
        assertEquals("Откройте настройки", SourceTextFilter.filter("Откройте настройки", "ru", "zh"))
        assertNull(SourceTextFilter.filter("Open settings", "ru", "zh"))
    }

    @Test
    fun `same source and target language skips translation`() {
        assertNull(SourceTextFilter.filter("This is already English.", "en", "en-US"))
    }

    @Test
    fun `block filtering keeps order and removes target language and values`() {
        assertEquals(
            listOf("Open settings", "Try again"),
            SourceTextFilter.filterBlocks(
                listOf("Open settings", "发送", "12:48", "Try again"),
                sourceLanguageTag = "en",
                targetLanguageTag = "zh",
            ),
        )
    }
}
