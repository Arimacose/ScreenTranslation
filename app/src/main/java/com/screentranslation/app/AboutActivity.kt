package com.screentranslation.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.screentranslation.app.model.preparation.ModelPreparationCoordinator
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.ui.UiStyleController
import java.util.concurrent.Executors

class AboutActivity : AppCompatActivity() {
    private val readinessExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "about-model-readiness").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        UiStyleController.apply(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.about_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )

        content.addView(titleView(getString(R.string.about_title)))
        val identityView = bodyView(
            buildIdentitySummary(getString(R.string.model_progress_verifying)),
        )
        content.addView(identityView)
        refreshIdentityAsync(identityView)
        content.addView(sectionView(getString(R.string.about_data_flow_title)))
        content.addView(bodyView(getString(dataFlowString())))
        if (BuildConfig.ONLINE_LLM) {
            content.addView(sectionView(getString(R.string.about_online_boundary_title)))
            content.addView(bodyView(onlineConfigurationSummary()))
            content.addView(bodyView(getString(R.string.about_online_boundary)))
        }
        content.addView(sectionView(getString(R.string.about_offline_documents)))
        DOCUMENTS.forEach { document ->
            content.addView(
                actionButton(getString(document.titleRes)) {
                    startActivity(TrustDocumentActivity.intent(this, document.assetName))
                },
            )
        }
        content.addView(sectionView(getString(R.string.about_project_links)))
        content.addView(actionButton(getString(R.string.about_source_link)) { openUrl(SOURCE_URL) })
        content.addView(actionButton(getString(R.string.about_release_link)) { openUrl(RELEASE_URL) })
        content.addView(actionButton(getString(R.string.about_issues_link)) { openUrl(ISSUES_URL) })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        readinessExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildIdentitySummary(readinessLabel: String): String {
        val preferences = AppPreferences(this)
        val descriptor = runCatching {
            ModelPreparationCoordinator(this).descriptor(
                preferences.sourceLanguage,
                preferences.targetLanguage,
            )
        }.getOrNull()
        val revision = descriptor?.revisions?.joinToString() ?: getString(R.string.about_runtime_selected)
        return getString(
            R.string.about_identity_summary,
            BuildConfig.VERSION_NAME,
            BuildConfig.APPLICATION_ID,
            BuildConfig.EDITION_ID,
            BuildConfig.OCR_BACKEND_ID,
            BuildConfig.TRANSLATION_BACKEND_ID,
            revision,
            readinessLabel,
        )
    }

    private fun refreshIdentityAsync(identityView: TextView) {
        val preferences = AppPreferences(this)
        readinessExecutor.execute {
            val ready = runCatching {
                ModelPreparationCoordinator(this).isReady(
                    preferences.sourceLanguage,
                    preferences.targetLanguage,
                )
            }.getOrDefault(false)
            runOnUiThread {
                if (!isDestroyed) {
                    identityView.text = buildIdentitySummary(
                        getString(
                            if (ready) {
                                R.string.model_state_ready
                            } else {
                                R.string.model_state_not_downloaded
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun dataFlowString(): Int = if (BuildConfig.ONLINE_LLM) {
        R.string.about_data_flow_online
    } else {
        R.string.about_data_flow_local
    }

    private fun onlineConfigurationSummary(): String = runCatching {
        val bridge = Class.forName(ONLINE_EDITION_BRIDGE_CLASS)
        bridge.getMethod("configurationSummary", Context::class.java)
            .invoke(null, this) as String
    }.getOrElse { getString(R.string.online_config_status_unavailable) }

    private fun titleView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun bodyView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setLineSpacing(0f, 1.2f)
    }

    private fun actionButton(text: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            minHeight = dp(48)
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class TrustDocument(val titleRes: Int, val assetName: String)

    companion object {
        private const val ONLINE_EDITION_BRIDGE_CLASS =
            "com.screentranslation.app.online.OnlineEditionBridge"
        private const val SOURCE_URL = "https://github.com/Arimacose/ScreenTranslation"
        private const val RELEASE_URL = "$SOURCE_URL/releases"
        private const val ISSUES_URL = "$SOURCE_URL/issues"
        private val DOCUMENTS = listOf(
            TrustDocument(R.string.about_privacy, "PRIVACY.md"),
            TrustDocument(R.string.about_security, "SECURITY.md"),
            TrustDocument(R.string.about_license, "APACHE-2.0.txt"),
            TrustDocument(R.string.about_third_party, "THIRD_PARTY_NOTICES.md"),
        )
    }
}
