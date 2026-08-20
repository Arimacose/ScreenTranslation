package com.screentranslation.app

import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.screentranslation.app.prefs.NormalizedRegionBounds
import com.screentranslation.app.prefs.RegionPresetEntry
import com.screentranslation.app.prefs.RegionPresetOrientation
import com.screentranslation.app.prefs.RegionPresetStore
import com.screentranslation.app.prefs.clampNormalizedRegion
import com.screentranslation.app.service.ScreenTranslationService
import com.screentranslation.app.ui.UiStyleController
import com.screentranslation.app.util.UserFacingErrorMapper
import java.util.Locale

/** Accessible editor for normalized, orientation-specific capture-region presets. */
class RegionPresetActivity : AppCompatActivity() {
    private lateinit var store: RegionPresetStore
    private lateinit var orientation: RegionPresetOrientation
    private lateinit var presetSpinner: Spinner
    private lateinit var nameView: EditText
    private lateinit var currentRegionView: TextView
    private lateinit var selectedRegionView: TextView
    private lateinit var applyButton: MaterialButton
    private lateinit var renameButton: MaterialButton
    private lateinit var deleteButton: MaterialButton
    private var entries = emptyList<RegionPresetEntry>()
    private var currentBounds: NormalizedRegionBounds? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        UiStyleController.apply(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.region_presets_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = RegionPresetStore(this)
        orientation = RegionPresetOrientation.from(resources.configuration)
        currentBounds = readCurrentBounds()
        setContentView(buildContent())
        refreshEntries(store.activeName)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        content.addView(titleView(getString(R.string.region_presets_title)))
        content.addView(bodyView(getString(R.string.region_presets_privacy)))
        content.addView(sectionView(getString(R.string.region_presets_current_region)))
        currentRegionView = bodyView(
            currentBounds?.let(::formatBounds)
                ?: getString(R.string.region_presets_no_current_region),
        )
        ViewCompat.setStateDescription(currentRegionView, currentRegionView.text)
        content.addView(currentRegionView)

        content.addView(sectionView(getString(R.string.region_presets_saved)))
        presetSpinner = Spinner(this).apply {
            minimumHeight = dp(48)
            contentDescription = getString(R.string.region_presets_saved)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    renderSelection(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    renderSelection(-1)
                }
            }
        }
        content.addView(
            presetSpinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        selectedRegionView = bodyView("")
        content.addView(selectedRegionView)

        nameView = EditText(this).apply {
            hint = getString(R.string.region_presets_name_hint)
            minHeight = dp(48)
            maxLines = 1
            contentDescription = getString(R.string.region_presets_name_hint)
        }
        content.addView(
            nameView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            actionButton(getString(R.string.region_presets_save_current)) { view ->
                val bounds = currentBounds
                if (bounds == null) {
                    toast(R.string.region_presets_no_current_region)
                    return@actionButton
                }
                val result = runCatching {
                    store.save(nameView.text.toString(), orientation, bounds)
                }
                result.onSuccess { name ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    refreshEntries(name)
                    toast(R.string.region_presets_saved_message)
                }.onFailure(::showInputError)
            },
        )

        applyButton = actionButton(getString(R.string.region_presets_apply)) { view ->
            selectedEntry()?.let { entry ->
                store.activeName = entry.name
                startService(
                    ScreenTranslationService.applyRegionPresetIntent(
                        context = this,
                        presetName = entry.name,
                        bounds = entry.bounds,
                    ),
                )
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                finish()
            }
        }
        content.addView(applyButton)

        val managementRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        renameButton = actionButton(getString(R.string.region_presets_rename)) { view ->
            val selected = selectedEntry() ?: return@actionButton
            runCatching { store.rename(selected.name, nameView.text.toString()) }
                .onSuccess { name ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    refreshEntries(name)
                    toast(R.string.region_presets_renamed_message)
                }
                .onFailure(::showInputError)
        }
        deleteButton = actionButton(getString(R.string.region_presets_delete)) { view ->
            val selected = selectedEntry() ?: return@actionButton
            if (store.delete(selected.name)) {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                refreshEntries(null)
                toast(R.string.region_presets_deleted_message)
            }
        }
        managementRow.addView(
            renameButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        managementRow.addView(
            deleteButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            },
        )
        content.addView(managementRow)
        content.addView(
            actionButton(getString(android.R.string.cancel)) { finish() },
        )

        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun refreshEntries(preferredName: String?) {
        entries = store.list(orientation)
        presetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            entries.map { it.name },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val target = preferredName ?: store.activeName
        val position = entries.indexOfFirst { it.name == target }.takeIf { it >= 0 } ?: 0
        if (entries.isNotEmpty()) presetSpinner.setSelection(position)
        renderSelection(position)
    }

    private fun renderSelection(position: Int) {
        val entry = entries.getOrNull(position)
        selectedRegionView.text = entry?.let {
            getString(
                R.string.region_presets_selected_summary,
                it.name,
                orientationLabel(),
                formatBounds(it.bounds),
            )
        } ?: getString(R.string.region_presets_none)
        ViewCompat.setStateDescription(selectedRegionView, selectedRegionView.text)
        if (entry != null && !nameView.hasFocus()) nameView.setText(entry.name)
        applyButton.isEnabled = entry != null
        val editable = entry != null && !entry.starter
        renameButton.isEnabled = editable
        deleteButton.isEnabled = editable
    }

    private fun selectedEntry(): RegionPresetEntry? =
        entries.getOrNull(presetSpinner.selectedItemPosition)

    private fun readCurrentBounds(): NormalizedRegionBounds? {
        if (!intent.hasExtra(EXTRA_LEFT)) return null
        return clampNormalizedRegion(
            intent.getFloatExtra(EXTRA_LEFT, 0f),
            intent.getFloatExtra(EXTRA_TOP, 0f),
            intent.getFloatExtra(EXTRA_RIGHT, 1f),
            intent.getFloatExtra(EXTRA_BOTTOM, 1f),
        )
    }

    private fun formatBounds(bounds: NormalizedRegionBounds): String = String.format(
        Locale.ROOT,
        "L %.3f · T %.3f · R %.3f · B %.3f",
        bounds.left,
        bounds.top,
        bounds.right,
        bounds.bottom,
    )

    private fun orientationLabel(): String = getString(
        if (orientation == RegionPresetOrientation.LANDSCAPE) {
            R.string.region_presets_landscape
        } else {
            R.string.region_presets_portrait
        },
    )

    private fun titleView(text: String) = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isAccessibilityHeading = true
    }

    private fun sectionView(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        isAccessibilityHeading = true
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun bodyView(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setLineSpacing(0f, 1.15f)
    }

    private fun actionButton(
        text: String,
        action: (View) -> Unit,
    ) = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener(action)
    }

    private fun showInputError(error: Throwable) {
        nameView.error = UserFacingErrorMapper.map(error).summary
        nameView.requestFocus()
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LEFT = "region_preset_left"
        const val EXTRA_TOP = "region_preset_top"
        const val EXTRA_RIGHT = "region_preset_right"
        const val EXTRA_BOTTOM = "region_preset_bottom"
    }
}
