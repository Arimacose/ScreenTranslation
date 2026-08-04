package com.screentranslation.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLayoutContractTest {
    @Test
    fun `Apple screens keep the standard interaction contract`() {
        assertLayoutParity(
            standard = "src/main/res/layout/activity_main.xml",
            apple = "src/main/res/layout/activity_main_apple.xml",
        )
        assertLayoutParity(
            standard = "src/main/res/layout/activity_model_management.xml",
            apple = "src/main/res/layout/activity_model_management_apple.xml",
        )
        assertLayoutParity(
            standard = "src/online/res/layout/activity_online_settings.xml",
            apple = "src/online/res/layout/activity_online_settings_apple.xml",
        )
    }

    @Test
    fun `Apple screens use an independent grouped visual language`() {
        val main = source("src/main/res/layout/activity_main_apple.xml")
        val models = source("src/main/res/layout/activity_model_management_apple.xml")
        val online = source("src/online/res/layout/activity_online_settings_apple.xml")
        val themes = source("src/main/res/values/themes.xml")
        val onlineStyles = source("src/online/res/values/styles.xml")

        listOf(main, models, online).forEach { layout ->
            assertTrue("TextAppearance.ScreenTranslation.Apple.LargeTitle" in layout)
            assertTrue("Widget.ScreenTranslation.Apple.Group" in layout)
            assertTrue("Apple layouts should not repaint the theme background", "@color/apple_background" !in layout)
        }
        assertTrue("<item name=\"android:windowBackground\">?attr/colorSurfaceContainerLowest</item>" in themes)
        assertTrue("<item name=\"colorSurfaceContainerLowest\">@color/apple_background</item>" in themes)
        assertTrue("Widget.ScreenTranslation.Apple.SegmentedButton" in main)
        assertTrue("@drawable/bg_apple_segmented_control" in main)
        assertTrue("Widget.ScreenTranslation.Apple.Input" in online)
        assertTrue("Widget.ScreenTranslation.Apple.Input" in onlineStyles)

        assertNotEquals(source("src/main/res/layout/activity_main.xml"), main)
        assertNotEquals(
            source("src/main/res/layout/activity_model_management.xml"),
            models,
        )
        assertNotEquals(source("src/online/res/layout/activity_online_settings.xml"), online)
    }

    private fun assertLayoutParity(standard: String, apple: String) {
        assertEquals(viewIds(source(standard)), viewIds(source(apple)))
    }

    private fun viewIds(layout: String): Set<String> = ID_PATTERN
        .findAll(layout)
        .mapTo(linkedSetOf()) { it.groupValues[1] }

    private fun source(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate layout source: $relativePath")
    }

    private companion object {
        val ID_PATTERN = Regex("""android:id="@\+id/([^"]+)"""")
    }
}
