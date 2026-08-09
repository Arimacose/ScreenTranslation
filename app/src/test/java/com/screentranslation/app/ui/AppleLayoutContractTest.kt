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

    @Test
    fun `font scale sensitive controls use compact complete labels in every style`() {
        val mainStrings = source("src/main/res/values/strings.xml")
        val onlineStrings = source("src/online/res/values/strings.xml")
        val standardOnline = source("src/online/res/layout/activity_online_settings.xml")
        val appleOnline = source("src/online/res/layout/activity_online_settings_apple.xml")

        assertEquals(
            "全屏增量覆盖（实验）",
            stringValue(mainStrings, "capture_mode_full_screen"),
        )
        assertEquals(
            "Base URL（如 https://HOST/v1）",
            stringValue(onlineStrings, "online_base_url_hint"),
        )
        assertEquals(
            "API Key（留空保留已保存密钥）",
            stringValue(onlineStrings, "online_api_key_hint"),
        )
        listOf(standardOnline, appleOnline).forEach { layout ->
            assertTrue("@string/online_base_url_hint" in layout)
            assertTrue("@string/online_api_key_hint" in layout)
        }
        assertTrue("/models" in stringValue(onlineStrings, "online_endpoint_path_help"))
        assertTrue("/chat/completions" in stringValue(onlineStrings, "online_endpoint_path_help"))
    }

    @Test
    fun `ready preparation button uses explicit neutral disabled colors in every style`() {
        val activity = source(
            "src/main/java/com/screentranslation/app/MainActivity.kt",
        )

        assertTrue("if (prepareState.isReady)" in activity)
        assertTrue("colorSurfaceVariant" in activity)
        assertTrue("colorOnSurfaceVariant" in activity)
        assertTrue(
            "prepareModelsButton.backgroundTintList = prepareModelsButtonDefaultTint" in activity,
        )
        assertTrue(
            "prepareModelsButton.setTextColor(prepareModelsButtonDefaultTextColors)" in activity,
        )
    }

    private fun assertLayoutParity(standard: String, apple: String) {
        assertEquals(viewIds(source(standard)), viewIds(source(apple)))
    }

    private fun viewIds(layout: String): Set<String> = ID_PATTERN
        .findAll(layout)
        .mapTo(linkedSetOf()) { it.groupValues[1] }

    private fun stringValue(resources: String, name: String): String = Regex(
        """<string name="$name">([^<]+)</string>""",
    ).find(resources)?.groupValues?.get(1)
        ?: error("Could not locate string resource: $name")

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
