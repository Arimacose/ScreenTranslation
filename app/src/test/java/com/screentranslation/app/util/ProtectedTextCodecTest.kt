package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedTextCodecTest {
    @Test
    fun `protects mixed values and restores them after translation`() {
        val original = "Version v0.3.1 costs ￥1,299.00 on 2026-08-03; see https://example.com/a?q=1."

        val protected = ProtectedTextCodec.protect(original)

        assertFalse(protected.encoded.contains("v0.3.1"))
        assertFalse(protected.encoded.contains("￥1,299.00"))
        assertFalse(protected.encoded.contains("2026-08-03"))
        assertFalse(protected.encoded.contains("https://example.com/a?q=1"))
        assertEquals(original, protected.restore(protected.encoded))
    }

    @Test
    fun `keeps surrounding punctuation outside a url token`() {
        val protected = ProtectedTextCodec.protect("Open (https://example.com/docs), now.")
        val translated = protected.encoded
            .replace("Open (", "打开（")
            .replace("), now.", "），现在。")

        assertEquals("打开（https://example.com/docs），现在。", protected.restore(translated))
    }

    @Test
    fun `stops an ascii url before adjacent CJK prose`() {
        val original = "访问https://example.com/releases/2.0查看说明，误差为3.14"
        val protected = ProtectedTextCodec.protect(original)

        assertTrue(protected.hasReplacements)
        assertTrue(protected.encoded.contains("查看说明，误差为"))
        assertFalse(protected.encoded.contains("https://example.com/releases/2.0"))
        assertEquals(original, protected.restore(protected.encoded))
    }

    @Test
    fun `protects a bare domain between adjacent CJK prose`() {
        val original = "访问example.com/docs查看说明"
        val protected = ProtectedTextCodec.protect(original)

        assertFalse(protected.encoded.contains("example.com/docs"))
        assertTrue(protected.encoded.contains("访问"))
        assertTrue(protected.encoded.contains("查看说明"))
        assertEquals(original, protected.restore(protected.encoded))
    }

    @Test
    fun `restores a token after the translator normalizes its brackets`() {
        val protected = ProtectedTextCodec.protect(
            "Visit https://example.com/help for details.",
        )
        val normalized = protected.encoded
            .replace('⟦', '[')
            .replace('⟧', ']')
            .replace("Visit ", "请访问 ")
            .replace(" for details.", " 了解详情。")

        assertEquals(
            "请访问 https://example.com/help 了解详情。",
            protected.restore(normalized),
        )
    }

    @Test
    fun `restores a token even when the translator removes its wrapper`() {
        val protected = ProtectedTextCodec.protect("Contact demo@example.com now.")
        val wrapperless = protected.encoded
            .replace("⟦", "")
            .replace("⟧", "")
            .replace("Contact ", "联系 ")
            .replace(" now.", "。")

        assertEquals("联系 demo@example.com。", protected.restore(wrapperless))
    }

    @Test
    fun `date wins over overlapping version pattern`() {
        val protected = ProtectedTextCodec.protect("Released 2026.08.03 with build 1.2.3-beta.")

        assertEquals(
            2,
            Regex("⟦STP_[0-9a-f]{8}_\\d{4}⟧").findAll(protected.encoded).count(),
        )
        assertEquals(
            "Released 2026.08.03 with build 1.2.3-beta.",
            protected.restore(protected.encoded),
        )
    }

    @Test
    fun `protects chinese and japanese amount forms`() {
        val original = "合计 88.50元，日本价格为12,800円，另收 USD 9.99。"
        val protected = ProtectedTextCodec.protect(original)

        assertTrue(protected.hasReplacements)
        assertEquals(original, protected.restore(protected.encoded))
    }

    @Test
    fun `uses a collision free token prefix`() {
        val original = "Literal ⟦STP_0000⟧ and version 2.5.0"
        val protected = ProtectedTextCodec.protect(original)

        assertTrue(protected.encoded.contains("⟦STP_0000⟧"))
        assertTrue(Regex("⟦STP_[0-9a-f]{8}_0000⟧").containsMatchIn(protected.encoded))
        assertEquals(original, protected.restore(protected.encoded))
    }

    @Test
    fun `different protected values produce different coordinator payloads`() {
        val first = ProtectedTextCodec.protect("Price is ￥12.00")
        val second = ProtectedTextCodec.protect("Price is ￥13.00")

        assertFalse(first.encoded == second.encoded)
        assertEquals("Price is ￥12.00", first.restore(first.encoded))
        assertEquals("Price is ￥13.00", second.restore(second.encoded))
    }

    @Test
    fun `returns unchanged text when nothing is protected`() {
        val original = "Translate this ordinary sentence."
        val protected = ProtectedTextCodec.protect(original)

        assertFalse(protected.hasReplacements)
        assertEquals(original, protected.encoded)
        assertEquals("普通译文", protected.restore("普通译文"))
    }
}
