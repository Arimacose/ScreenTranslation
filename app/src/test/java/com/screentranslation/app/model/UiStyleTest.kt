package com.screentranslation.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UiStyleTest {
    @Test
    fun `Apple is the default visual language`() {
        assertEquals(UiStyle.APPLE, UiStyle.DEFAULT)
        assertEquals(UiStyle.APPLE, UiStyle.fromPersisted(null))
        assertEquals(UiStyle.APPLE, UiStyle.fromPersisted("legacy"))
    }

    @Test
    fun `all supported visual languages round trip`() {
        UiStyle.entries.forEach { style ->
            assertEquals(style, UiStyle.fromPersisted(style.persistedValue))
        }
    }

    @Test
    fun `Monet is scoped to Material 3 and remains user controllable`() {
        assertEquals(false, shouldApplyMaterialMonet(UiStyle.APPLE, true))
        assertEquals(false, shouldApplyMaterialMonet(UiStyle.MIUIX, true))
        assertEquals(false, shouldApplyMaterialMonet(UiStyle.MATERIAL3, false))
        assertEquals(true, shouldApplyMaterialMonet(UiStyle.MATERIAL3, true))
    }
}
