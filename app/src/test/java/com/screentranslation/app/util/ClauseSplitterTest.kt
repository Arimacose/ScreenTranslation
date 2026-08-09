package com.screentranslation.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClauseSplitterTest {

    @Test
    fun `leaves a short sentence alone`() {
        val text = "Select the area you want to translate."
        assertEquals(listOf(text), ClauseSplitter.split(text))
    }

    @Test
    fun `leaves menu labels alone`() {
        for (label in listOf("Storage", "Battery and performance", "Notifications and control center")) {
            assertEquals("'$label' must not be split", listOf(label), ClauseSplitter.split(label))
        }
    }

    /**
     * The sentence that ML Kit mistranslated on device: it inverted the negation
     * and dropped the "never leaves the phone" clause entirely.
     */
    @Test
    fun `splits the sentence that was mistranslated on device`() {
        val text = "The translation engine runs entirely on your device, which means the " +
            "text captured from the screen never leaves the phone and the model keeps " +
            "working even when there is no network connection available at all."

        val parts = ClauseSplitter.split(text)

        assertEquals(
            listOf(
                "The translation engine runs entirely on your device",
                "which means the text captured from the screen never leaves the phone",
                "and the model keeps working even when there is no network connection " +
                    "available at all.",
            ),
            parts,
        )
    }

    @Test
    fun `splits on unless`() {
        val text = "Some manufacturers reclaim foreground services aggressively, so a long " +
            "running capture session may be interrupted unless the app is allowed to run " +
            "without restrictions."

        val parts = ClauseSplitter.split(text)

        assertTrue("expected multiple clauses, got $parts", parts.size >= 2)
        assertTrue(parts.any { it.startsWith("unless") || it.startsWith("so ") })
    }

    @Test
    fun `splits long independent clauses at a semicolon`() {
        val text = "The app keeps screen content in memory only while translation is active; " +
            "the captured text is discarded as soon as the current frame has been processed."

        assertEquals(
            listOf(
                "The app keeps screen content in memory only while translation is active",
                "the captured text is discarded as soon as the current frame has been processed.",
            ),
            ClauseSplitter.split(text),
        )
    }

    @Test
    fun `splits long Japanese text at sentence terminators without dropping them`() {
        val text = "翻訳モデルは端末上で動作し、画面から読み取った文章は外部へ送信されません。" +
            "ネットワークに接続できない場合でも、準備済みのモデルを使って処理を続けられます。" +
            "停止すると使用していたリソースはすぐに解放されます。"

        val parts = ClauseSplitter.split(text)

        assertEquals(3, parts.size)
        assertTrue(parts.all { it.endsWith("。") })
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun `splits long English text at restored sentence terminators`() {
        val text = "The storm crossed the northern valley before midnight while every station " +
            "reported lower water levels. The rescue team reopened the mountain road after " +
            "engineers inspected every bridge and radio relay."

        val parts = ClauseSplitter.split(text)

        assertEquals(2, parts.size)
        assertTrue(parts.all { it.endsWith(".") })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test
    fun `does not treat protected internal punctuation as clause boundaries`() {
        val text = "During a deliberately extended inspection the measured value stayed at 3.14 " +
            "beside release v2.0.0 located at https://example.com/releases/2.0 until the final " +
            "review concluded with no separate semantic boundary"

        assertEquals(listOf(text), ClauseSplitter.split(text))
    }

    @Test
    fun `prefers the longer connector over the one nested inside it`() {
        val text = "The app keeps working offline until you clear its data, in which case " +
            "the models have to be downloaded again from the network."

        val parts = ClauseSplitter.split(text)

        assertTrue(
            "'in which case' must win over the ', which ' nested in it, got $parts",
            parts.any { it.startsWith("in which case") },
        )
    }

    @Test
    fun `does not split when no connector is present`() {
        val text = "This is a single long clause about screen translation that simply keeps " +
            "going without any coordinating connector at all in the whole thing."

        assertEquals(listOf(text), ClauseSplitter.split(text))
    }

    @Test
    fun `never leaves a stub on either side`() {
        val text = "A very long introductory clause that carries most of the sentence weight " +
            "and describes the situation in detail, so ok."

        for (part in ClauseSplitter.split(text)) {
            assertTrue("stub produced: '$part'", part.length >= 3)
        }
    }

    @Test
    fun `reassembling the pieces preserves every word`() {
        val text = "The translation engine runs entirely on your device, which means the " +
            "text never leaves the phone and the model keeps working offline."

        val parts = ClauseSplitter.split(text)
        val rejoined = parts.joinToString(" ").replace(Regex("\\s+"), " ")
        val expected = text.replace(",", "").replace(Regex("\\s+"), " ")

        assertEquals(
            "splitting must only drop the separator comma, never a word",
            expected,
            rejoined.replace(",", ""),
        )
    }

    @Test
    fun `keeps splitting a sentence with several connectors`() {
        val text = "The app does not upload anything at all, and it will keep working offline " +
            "unless you clear its application data, in which case every model has to be " +
            "downloaded again before translation can resume."

        val parts = ClauseSplitter.split(text)

        assertTrue("expected at least three clauses, got ${parts.size}: $parts", parts.size >= 3)
    }

    @Test
    fun `handles trailing and leading whitespace`() {
        val text = "   Select the area you want to translate.   "
        assertEquals(listOf("Select the area you want to translate."), ClauseSplitter.split(text))
    }

    @Test
    fun `does not split a long clause on a connector that would strand a fragment`() {
        // "or" sits near the very end; splitting there would leave a stub.
        val text = "This sentence is deliberately long enough to be considered for splitting " +
            "by the clause splitter, or so."

        for (part in ClauseSplitter.split(text)) {
            assertTrue("stub produced: '$part'", part.length >= 10)
        }
    }

    @Test
    fun `reassembles clauses inline while keeping OCR blocks on separate lines`() {
        val longBlock = "The translation engine runs entirely on your device, which means the " +
            "text never leaves the phone and the model keeps working offline."
        val plan = ClauseSplitter.plan(listOf(longBlock, "Storage"))
        val translations = plan.parts.indices.map { "translation-$it" }
        val firstBlockParts = ClauseSplitter.split(longBlock).size

        assertEquals(
            translations.take(firstBlockParts).joinToString(" ") +
                "\n" +
                translations.drop(firstBlockParts).single(),
            plan.reassemble(translations),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a translation count that does not match the plan`() {
        ClauseSplitter.plan(listOf("Storage")).reassemble(emptyList())
    }
}
