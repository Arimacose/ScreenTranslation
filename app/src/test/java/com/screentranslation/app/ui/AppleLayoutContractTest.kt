package com.screentranslation.app.ui

import java.io.File
import java.security.MessageDigest
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
            "Base URL",
            stringValue(onlineStrings, "online_base_url_hint"),
        )
        assertEquals(
            "API Key",
            stringValue(onlineStrings, "online_api_key_hint"),
        )
        listOf(standardOnline, appleOnline).forEach { layout ->
            assertTrue("@string/online_base_url_hint" in layout)
            assertTrue("@string/online_api_key_hint" in layout)
        }
        val endpointHelp = stringValue(onlineStrings, "online_endpoint_path_help")
        assertTrue("https://HOST/v1" in endpointHelp)
        assertTrue("API Key 留空会保留已保存密钥" in endpointHelp)
        assertTrue("/models" in endpointHelp)
        assertTrue("/chat/completions" in endpointHelp)
    }

    @Test
    fun `full screen hint describes the accepted platform baseline`() {
        val mainStrings = source("src/main/res/values/strings.xml")
        val hint = stringValue(mainStrings, "capture_mode_full_screen_hint")

        assertTrue("Android 16" in hint)
        assertTrue("HyperOS" in hint)
        assertTrue("\u5c1a\u672a\u5b8c\u6210" !in hint)
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

    @Test
    fun `home readiness guidance is large and uses the active primary color`() {
        val standard = source("src/main/res/layout/activity_main.xml")
        val apple = source("src/main/res/layout/activity_main_apple.xml")
        val styles = source("src/main/res/values/styles.xml")

        assertTrue("TextAppearance.ScreenTranslation.Readiness" in standard)
        assertTrue("TextAppearance.ScreenTranslation.Apple.Readiness" in apple)
        assertTrue("<item name=\"android:textSize\">18sp</item>" in styles)
        assertTrue("<item name=\"android:textColor\">?attr/colorPrimary</item>" in styles)
        assertTrue("<item name=\"android:textColor\">@color/apple_primary</item>" in styles)
    }

    @Test
    fun `v2 3 accessibility contract keeps targets states and fast labels separated`() {
        val standard = source("src/main/res/layout/activity_main.xml")
        val apple = source("src/main/res/layout/activity_main_apple.xml")
        val model = source("src/main/res/layout/activity_model_management.xml")
        val online = source("src/online/res/layout/activity_online_settings.xml")
        val styles = source("src/main/res/values/styles.xml")
        val overlay = source(
            "src/main/java/com/screentranslation/app/overlay/FullScreenOverlayController.kt",
        )
        val mainActivity = source("src/main/java/com/screentranslation/app/MainActivity.kt")

        listOf(standard, apple, model, online).forEach { layout ->
            assertTrue("44dp target remains in an interactive layout", "minHeight=\"44dp\"" !in layout)
        }
        assertTrue("screenControlMinHeight" in styles)
        assertTrue("minHeight = dp(48)" in overlay)
        assertTrue("IMPORTANT_FOR_ACCESSIBILITY_NO" in overlay)
        assertTrue("renderReadingSurface" in overlay)
        assertTrue("full_screen_reading_item_description" in overlay)
        assertTrue("ViewCompat.setStateDescription" in mainActivity)
    }

    @Test
    fun `rendered accessibility matrix is the reviewed deterministic golden`() {
        val script = source("scripts/generate_ui_style_preview.py")
        listOf(
            "Light · Portrait · 1.0×",
            "Light · Landscape · 1.3×",
            "Night · Portrait · 2.0×",
            "Night · Landscape · 2.0×",
        ).forEach { assertTrue(it in script) }
        assertTrue("Material 3 + Monet" in script)

        val golden = sourceFile("docs/assets/ui-accessibility-matrix.png")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(golden.readBytes())
            .joinToString("") { "%02X".format(it) }
        assertEquals(
            "5AF4A9958D2AD055CD8E59139E79E97A312F7B0683AB9B10B4431A68C7405074",
            digest,
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
        return sourceFile(relativePath).readText()
    }

    private fun sourceFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("..", relativePath),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Could not locate layout source: $relativePath")
    }

    private companion object {
        val ID_PATTERN = Regex("""android:id="@\+id/([^"]+)"""")
    }
}
