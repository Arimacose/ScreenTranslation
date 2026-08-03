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
