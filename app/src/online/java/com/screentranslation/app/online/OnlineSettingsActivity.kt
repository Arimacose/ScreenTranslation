package com.screentranslation.app.online

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.screentranslation.app.R
import com.screentranslation.app.ml.OnlineLlmTranslationEngine
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.prefs.AppPreferences

class OnlineSettingsActivity : AppCompatActivity() {
    private lateinit var repository: OnlineTranslationConfigRepository
    private lateinit var preferences: AppPreferences
    private lateinit var baseUrlView: EditText
    private lateinit var modelIdView: EditText
    private lateinit var apiKeyView: EditText
    private lateinit var keyStatusView: TextView
    private lateinit var consentView: CheckBox
    private lateinit var saveButton: Button
    private lateinit var testTextView: EditText
    private lateinit var saveAndTestButton: Button
    private lateinit var deleteKeyButton: Button
    private lateinit var resultView: TextView

    private var testEngine: TranslationBackend? = null
    private var testGeneration = 0L
    private var savedConsentHost = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_online_settings)
        repository = OnlineTranslationConfigRepository(this)
        preferences = AppPreferences(this)
        bindViews()
        loadConfiguration()
        configureActions()
    }

    override fun onDestroy() {
        testGeneration += 1L
        testEngine?.close()
        testEngine = null
        super.onDestroy()
    }

    private fun bindViews() {
        baseUrlView = findViewById(R.id.edit_online_base_url)
        modelIdView = findViewById(R.id.edit_online_model_id)
        apiKeyView = findViewById(R.id.edit_online_api_key)
        keyStatusView = findViewById(R.id.text_online_key_status)
        consentView = findViewById(R.id.check_online_consent)
        saveButton = findViewById(R.id.button_online_save)
        testTextView = findViewById(R.id.edit_online_test_text)
        saveAndTestButton = findViewById(R.id.button_online_save_test)
        deleteKeyButton = findViewById(R.id.button_online_delete_key)
        resultView = findViewById(R.id.text_online_result)
    }

    private fun loadConfiguration() {
        val config = repository.load()
        baseUrlView.setText(config.baseUrl)
        modelIdView.setText(config.modelId)
        apiKeyView.text.clear()
        savedConsentHost = config.consentHost
        consentView.isChecked =
            config.consentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION &&
                config.consentHost.isNotBlank()
        if (testTextView.text.isBlank()) {
            testTextView.setText(R.string.online_default_test_text)
        }
        refreshKeyStatus()
    }

    private fun configureActions() {
        baseUrlView.doAfterTextChanged { text ->
            val currentHost = runCatching {
                OpenAiEndpoint.parse(text?.toString().orEmpty()).consentIdentity
            }.getOrNull()
            if (currentHost == null || currentHost != savedConsentHost) {
                consentView.isChecked = false
            }
        }
        saveButton.setOnClickListener {
            saveConfiguration().fold(
                onSuccess = { config ->
                    resultView.text = getString(
                        R.string.online_save_success,
                        OpenAiEndpoint.parse(config.baseUrl).requestUrl,
                    )
                },
                onFailure = ::showFailure,
            )
        }
        saveAndTestButton.setOnClickListener {
            saveConfiguration().fold(
                onSuccess = { runTranslationTest() },
                onFailure = ::showFailure,
            )
        }
        deleteKeyButton.setOnClickListener {
            testGeneration += 1L
            testEngine?.close()
            testEngine = null
            repository.deleteApiKey()
            apiKeyView.text.clear()
            refreshKeyStatus()
            resultView.setText(R.string.online_key_deleted)
            setResult(Activity.RESULT_OK)
        }
    }

    private fun saveConfiguration(): Result<OnlineTranslationConfig> = runCatching {
        val config = repository.save(
            baseUrl = baseUrlView.text.toString(),
            modelId = modelIdView.text.toString(),
            newApiKey = apiKeyView.text.toString().takeIf { it.isNotBlank() },
            consentAccepted = consentView.isChecked,
        )
        savedConsentHost = config.consentHost
        consentView.isChecked = true
        apiKeyView.text.clear()
        refreshKeyStatus()
        setResult(Activity.RESULT_OK)
        config
    }

    private fun runTranslationTest() {
        val sourceText = testTextView.text.toString().trim()
        if (sourceText.isEmpty()) {
            showFailure(IllegalArgumentException("Test text is blank"))
            return
        }
        val generation = ++testGeneration
        testEngine?.close()
        val engine = runCatching {
            OnlineLlmTranslationEngine(
                context = this,
                sourceLanguage = preferences.sourceLanguage,
                targetLanguage = preferences.targetLanguage,
            )
        }.getOrElse { error ->
            showFailure(error)
            return
        }
        testEngine = engine
        setTestingUi(true)
        resultView.setText(R.string.online_test_running)
        engine.prepare(warmRuntime = false) { preparation ->
            preparation.fold(
                onSuccess = {
                    engine.translate(sourceText) { translation ->
                        runOnUiThread {
                            finishTranslationTest(generation, engine, translation)
                        }
                    }
                },
                onFailure = { error ->
                    runOnUiThread {
                        finishTranslationTest(generation, engine, Result.failure(error))
                    }
                },
            )
        }
    }

    private fun finishTranslationTest(
        generation: Long,
        engine: TranslationBackend,
        result: Result<String>,
    ) {
        if (generation != testGeneration || isDestroyed) {
            engine.close()
            return
        }
        if (testEngine === engine) testEngine = null
        engine.close()
        setTestingUi(false)
        result.fold(
            onSuccess = { translated ->
                resultView.text = getString(R.string.online_test_success, translated)
            },
            onFailure = ::showFailure,
        )
    }

    private fun setTestingUi(testing: Boolean) {
        saveButton.isEnabled = !testing
        saveAndTestButton.isEnabled = !testing
        deleteKeyButton.isEnabled = !testing
    }

    private fun refreshKeyStatus() {
        keyStatusView.setText(
            if (repository.hasApiKey()) {
                R.string.online_key_saved
            } else {
                R.string.online_key_missing
            },
        )
    }

    private fun showFailure(error: Throwable) {
        setTestingUi(false)
        refreshKeyStatus()
        resultView.text = getString(
            R.string.online_test_failed,
            error.localizedMessage ?: error.javaClass.simpleName,
        )
    }
}
