package com.screentranslation.app

import android.app.Activity
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.screentranslation.app.model.ManagedModel
import com.screentranslation.app.model.ModelDownloadState
import com.screentranslation.app.model.ModelStorageManager
import com.screentranslation.app.model.ModelStorageManagerFactory
import com.screentranslation.app.service.ScreenTranslationService
import com.screentranslation.app.ui.UiStyleController

class ModelManagementActivity : AppCompatActivity() {
    companion object {
        const val RESULT_MODELS_CHANGED = Activity.RESULT_FIRST_USER
    }

    private lateinit var manager: ModelStorageManager
    private lateinit var summaryView: TextView
    private lateinit var deleteButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        UiStyleController.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_management)
        title = getString(R.string.model_management_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        manager = ModelStorageManagerFactory.create(this)
        summaryView = findViewById(R.id.text_model_inventory)
        deleteButton = findViewById(R.id.button_delete_models)
        applySystemBarInsets()
        findViewById<Button>(R.id.button_prepare_models).setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
        findViewById<Button>(R.id.button_refresh_models).setOnClickListener { refresh() }
        deleteButton.setOnClickListener { confirmDeletion() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val snapshots = manager.scan()
        summaryView.text = buildSummary(snapshots)
        val hasDownloadedFiles = snapshots.any { it.downloadedBytes > 0L }
        deleteButton.visibility = if (snapshots.isEmpty()) View.GONE else View.VISIBLE
        deleteButton.isEnabled = hasDownloadedFiles && !ScreenTranslationService.isRunning
    }

    private fun buildSummary(models: List<ManagedModel>): String = buildString {
        appendLine(getString(R.string.model_inventory_ocr_bundled))
        if (models.isEmpty()) {
            append(getString(R.string.model_inventory_online_only))
            return@buildString
        }
        models.forEachIndexed { index, model ->
            if (index > 0) appendLine().appendLine()
            appendLine(model.displayName)
            appendLine(getString(R.string.model_inventory_state, stateLabel(model.state)))
            appendLine(
                getString(
                    R.string.model_inventory_size,
                    Formatter.formatFileSize(this@ModelManagementActivity, model.downloadedBytes),
                    model.expectedBytes?.let {
                        Formatter.formatFileSize(this@ModelManagementActivity, it)
                    } ?: getString(R.string.model_inventory_size_variable),
                ),
            )
            append(getString(R.string.model_inventory_revision, model.revision))
        }
        if (ScreenTranslationService.isRunning) {
            appendLine().appendLine().append(getString(R.string.model_delete_service_running))
        }
    }

    private fun stateLabel(state: ModelDownloadState): String = getString(
        when (state) {
            ModelDownloadState.NOT_DOWNLOADED -> R.string.model_state_not_downloaded
            ModelDownloadState.PARTIAL -> R.string.model_state_partial
            ModelDownloadState.READY -> R.string.model_state_ready
        },
    )

    private fun confirmDeletion() {
        if (ScreenTranslationService.isRunning) {
            refresh()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.model_delete_title)
            .setMessage(R.string.model_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.model_delete_confirm) { _, _ -> deleteModels() }
            .show()
    }

    private fun deleteModels() {
        deleteButton.isEnabled = false
        summaryView.setText(R.string.model_deleting)
        Thread {
            val result = runCatching {
                check(!ScreenTranslationService.isRunning) {
                    getString(R.string.model_delete_service_running)
                }
                manager.deleteDownloadedModels()
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { bytes ->
                        setResult(RESULT_MODELS_CHANGED)
                        refresh()
                        summaryView.append(
                            "\n\n" + getString(
                                R.string.model_delete_success,
                                Formatter.formatFileSize(this, bytes),
                            ),
                        )
                    },
                    onFailure = { error ->
                        refresh()
                        summaryView.append(
                            "\n\n" + getString(
                                R.string.model_delete_failed,
                                error.localizedMessage ?: error.javaClass.simpleName,
                            ),
                        )
                    },
                )
            }
        }.start()
    }

    private fun applySystemBarInsets() {
        val content = findViewById<View>(android.R.id.content)
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content.requestApplyInsets()
    }
}
