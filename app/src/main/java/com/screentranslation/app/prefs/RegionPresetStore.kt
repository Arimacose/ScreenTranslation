package com.screentranslation.app.prefs

import android.content.Context
import android.content.res.Configuration
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class RegionPresetOrientation(val storageToken: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    ;

    companion object {
        fun from(configuration: Configuration): RegionPresetOrientation =
            fromOrientationValue(configuration.orientation)

        fun fromOrientationValue(value: Int): RegionPresetOrientation =
            if (value == Configuration.ORIENTATION_LANDSCAPE) {
                LANDSCAPE
            } else {
                PORTRAIT
            }
    }
}

data class NormalizedRegionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f)
        require(right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }
}

data class RegionPresetEntry(
    val name: String,
    val bounds: NormalizedRegionBounds,
    val starter: Boolean,
)

/**
 * Clamps untrusted or stale normalized coordinates to a usable capture region.
 * Resolution and density never enter storage; callers convert to physical pixels
 * only when applying a preset to the current display.
 */
fun clampNormalizedRegion(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    minimumSpan: Float = 0.03f,
): NormalizedRegionBounds {
    require(minimumSpan > 0f && minimumSpan <= 1f)

    fun finite(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    fun clampAxis(first: Float, second: Float): Pair<Float, Float> {
        var start = minOf(finite(first, 0f), finite(second, 1f)).coerceIn(0f, 1f)
        var end = maxOf(finite(first, 0f), finite(second, 1f)).coerceIn(0f, 1f)
        if (end - start < minimumSpan) {
            val center = ((start + end) / 2f).coerceIn(0f, 1f)
            start = (center - minimumSpan / 2f).coerceAtLeast(0f)
            end = start + minimumSpan
            if (end > 1f) {
                end = 1f
                start = 1f - minimumSpan
            }
        }
        return start to end
    }

    val horizontal = clampAxis(left, right)
    val vertical = clampAxis(top, bottom)
    return NormalizedRegionBounds(
        left = horizontal.first,
        top = vertical.first,
        right = horizontal.second,
        bottom = vertical.second,
    )
}

/**
 * Stores region geometry and preset preferences only. It never receives image,
 * OCR, translation, foreground-app, or accessibility-service data.
 */
class RegionPresetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        ensureStarterPresets()
    }

    fun list(orientation: RegionPresetOrientation): List<RegionPresetEntry> =
        storedNames()
            .mapNotNull { name ->
                load(name, orientation)?.let { bounds ->
                    RegionPresetEntry(name, bounds, name in STARTER_NAMES)
                }
            }
            .sortedWith(
                compareBy<RegionPresetEntry>({ !it.starter }, { it.name.lowercase() }),
            )

    fun load(
        name: String,
        orientation: RegionPresetOrientation,
    ): NormalizedRegionBounds? {
        val prefix = coordinatePrefix(name, orientation)
        if (!preferences.contains("$prefix.left")) return null
        return clampNormalizedRegion(
            left = preferences.getFloat("$prefix.left", 0f),
            top = preferences.getFloat("$prefix.top", 0f),
            right = preferences.getFloat("$prefix.right", 1f),
            bottom = preferences.getFloat("$prefix.bottom", 1f),
        )
    }

    fun save(
        rawName: String,
        orientation: RegionPresetOrientation,
        bounds: NormalizedRegionBounds,
    ): String {
        val name = normalizedName(rawName)
        val names = storedNames().toMutableSet().apply { add(name) }
        preferences.edit()
            .putStringSet(KEY_NAMES, names)
            .putBounds(name, orientation, bounds)
            .apply()
        return name
    }

    fun rename(oldName: String, rawNewName: String): String {
        require(oldName !in STARTER_NAMES) { "Starter presets keep their localized names" }
        val newName = normalizedName(rawNewName)
        require(newName !in STARTER_NAMES) { "Choose a different preset name" }
        require(newName == oldName || newName !in storedNames()) { "Preset already exists" }
        if (newName == oldName) return oldName

        val portrait = load(oldName, RegionPresetOrientation.PORTRAIT)
        val landscape = load(oldName, RegionPresetOrientation.LANDSCAPE)
        val names = storedNames().toMutableSet().apply {
            remove(oldName)
            add(newName)
        }
        val editor = preferences.edit().putStringSet(KEY_NAMES, names)
        portrait?.let { editor.putBounds(newName, RegionPresetOrientation.PORTRAIT, it) }
        landscape?.let { editor.putBounds(newName, RegionPresetOrientation.LANDSCAPE, it) }
        removeBounds(editor, oldName)
        if (activeName == oldName) editor.putString(KEY_ACTIVE_NAME, newName)
        editor.apply()
        return newName
    }

    fun delete(name: String): Boolean {
        if (name in STARTER_NAMES || name !in storedNames()) return false
        val names = storedNames().toMutableSet().apply { remove(name) }
        val editor = preferences.edit().putStringSet(KEY_NAMES, names)
        removeBounds(editor, name)
        if (activeName == name) editor.remove(KEY_ACTIVE_NAME)
        editor.apply()
        return true
    }

    var activeName: String?
        get() = preferences.getString(KEY_ACTIVE_NAME, null)
            ?.takeIf { it in storedNames() }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_ACTIVE_NAME) else putString(
                    KEY_ACTIVE_NAME,
                    normalizedName(value).also { require(it in storedNames()) },
                )
            }.apply()
        }

    private fun ensureStarterPresets() {
        val names = storedNames().toMutableSet()
        val editor = preferences.edit()
        STARTERS.forEach { starter ->
            names += starter.name
            RegionPresetOrientation.entries.forEach { orientation ->
                if (load(starter.name, orientation) == null) {
                    editor.putBounds(
                        starter.name,
                        orientation,
                        if (orientation == RegionPresetOrientation.PORTRAIT) {
                            starter.portrait
                        } else {
                            starter.landscape
                        },
                    )
                }
            }
        }
        editor.putStringSet(KEY_NAMES, names).apply()
    }

    private fun storedNames(): Set<String> =
        preferences.getStringSet(KEY_NAMES, emptySet())?.toSet().orEmpty()

    private fun android.content.SharedPreferences.Editor.putBounds(
        name: String,
        orientation: RegionPresetOrientation,
        bounds: NormalizedRegionBounds,
    ): android.content.SharedPreferences.Editor {
        val prefix = coordinatePrefix(name, orientation)
        return putFloat("$prefix.left", bounds.left)
            .putFloat("$prefix.top", bounds.top)
            .putFloat("$prefix.right", bounds.right)
            .putFloat("$prefix.bottom", bounds.bottom)
    }

    private fun removeBounds(
        editor: android.content.SharedPreferences.Editor,
        name: String,
    ) {
        RegionPresetOrientation.entries.forEach { orientation ->
            val prefix = coordinatePrefix(name, orientation)
            listOf("left", "top", "right", "bottom").forEach { coordinate ->
                editor.remove("$prefix.$coordinate")
            }
        }
    }

    private fun coordinatePrefix(
        name: String,
        orientation: RegionPresetOrientation,
    ): String = "preset.${encodeName(name)}.${orientation.storageToken}"

    private fun encodeName(name: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(name.toByteArray(StandardCharsets.UTF_8))

    private fun normalizedName(rawName: String): String {
        val name = rawName.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)
        require(name.isNotBlank()) { "Preset name is required" }
        return name
    }

    private data class StarterPreset(
        val name: String,
        val portrait: NormalizedRegionBounds,
        val landscape: NormalizedRegionBounds,
    )

    companion object {
        const val STARTER_BOTTOM_SUBTITLES = "底部字幕"
        const val STARTER_CENTER_DIALOG = "中心对话"

        private const val PREFERENCES_NAME = "region_presets_v1"
        private const val KEY_NAMES = "names"
        private const val KEY_ACTIVE_NAME = "active_name"
        private const val MAX_NAME_LENGTH = 40

        private val STARTERS = listOf(
            StarterPreset(
                name = STARTER_BOTTOM_SUBTITLES,
                portrait = NormalizedRegionBounds(0.05f, 0.68f, 0.95f, 0.92f),
                landscape = NormalizedRegionBounds(0.08f, 0.62f, 0.92f, 0.92f),
            ),
            StarterPreset(
                name = STARTER_CENTER_DIALOG,
                portrait = NormalizedRegionBounds(0.10f, 0.28f, 0.90f, 0.68f),
                landscape = NormalizedRegionBounds(0.16f, 0.18f, 0.84f, 0.82f),
            ),
        )
        private val STARTER_NAMES = STARTERS.mapTo(linkedSetOf()) { it.name }
    }
}
