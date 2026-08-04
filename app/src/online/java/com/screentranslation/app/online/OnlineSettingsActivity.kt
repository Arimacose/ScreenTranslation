package com.screentranslation.app.online

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.screentranslation.app.R
import com.screentranslation.app.ml.OnlineLlmTranslationEngine
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationCall
import com.screentranslation.app.model.UiStyle
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.ui.UiStyleController
import okhttp3.OkHttpClient

class OnlineSettingsActivity : AppCompatActivity() {
    private lateinit var repository: OnlineTranslationConfigRepository
    private lateinit var preferences: AppPreferences
    private lateinit var modelHttpClient: OkHttpClient
    private lateinit var baseUrlView: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var modelAdapter: ArrayAdapter<String>
    private lateinit var modelStatusView: TextView
    private lateinit var apiKeyView: EditText
    private lateinit var keyStatusView: TextView
    private lateinit var consentView: CheckBox
    private lateinit var fetchModelsButton: Button
    private lateinit var saveButton: Button
    private lateinit var testTextView: EditText
    private lateinit var saveAndTestButton: Button
    private lateinit var deleteKeyButton: Button
    private lateinit var resultView: TextView

    private val modelIds = mutableListOf<String>()
    private val modelDisplayItems = mutableListOf<String>()
    private var modelCatalogBaseUrl = ""
    private var modelFetchCall: TranslationCall = TranslationCall.NONE
    private var modelFetchGeneration = 0L
    private var fetchingModels = false
    private var testingTranslation = false
    private var suppressConfigWatchers = false
    private var testEngine: TranslationBackend? = null
    private var testGeneration = 0L
    private var savedUserConsentHost = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        val uiStyle = UiStyleController.apply(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(
            if (uiStyle == UiStyle.APPLE) {
                R.layout.activity_online_settings_apple
            } else {
                R.layout.activity_online_settings
            },
        )
        repository = OnlineTranslationConfigRepository(this)
        preferences = AppPreferences(this)
        modelHttpClient = OnlineHttpClientFactory.create()
        bindViews()
        applySystemBarInsets()
        loadConfiguration()
        configureActions()
    }

    override fun onDestroy() {
        cancelModelFetch()
        testGeneration += 1L
        testEngine?.close()
        testEngine = null
        OnlineHttpClientFactory.closeAsync(modelHttpClient)
        super.onDestroy()
    }

    private fun bindViews() {
        baseUrlView = findViewById(R.id.edit_online_base_url)
        modelSpinner = findViewById(R.id.spinner_online_model)
        modelStatusView = findViewById(R.id.text_online_model_status)
        apiKeyView = findViewById(R.id.edit_online_api_key)
        keyStatusView = findViewById(R.id.text_online_key_status)
        consentView = findViewById(R.id.check_online_consent)
        fetchModelsButton = findViewById(R.id.button_online_fetch_models)
        saveButton = findViewById(R.id.button_online_save)
        testTextView = findViewById(R.id.edit_online_test_text)
        saveAndTestButton = findViewById(R.id.button_online_save_test)
        deleteKeyButton = findViewById(R.id.button_online_delete_key)
        resultView = findViewById(R.id.text_online_result)
        modelAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            modelDisplayItems,
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = adapter
        }
    }

    private fun loadConfiguration() {
        val config = repository.load()
        suppressConfigWatchers = true
        baseUrlView.setText(config.baseUrl)
        apiKeyView.text.clear()
        suppressConfigWatchers = false
        savedUserConsentHost = config.consentHost
        if (config.modelId.isNotBlank()) {
            setModelOptions(listOf(config.modelId), config.modelId)
            modelCatalogBaseUrl = runCatching {
                OpenAiEndpoint.parse(config.baseUrl).baseUrl
            }.getOrDefault("")
            modelStatusView.text = getString(
                R.string.online_models_saved_selection,
                config.modelId,
            )
        } else {
            setModelOptions(emptyList(), null)
            modelStatusView.setText(R.string.online_models_not_loaded)
        }
        if (testTextView.text.isBlank()) {
            testTextView.setText(R.string.online_default_test_text)
        }
        consentView.isChecked = hasCurrentConsent()
        refreshKeyStatus()
        updateActionState()
    }

    private fun configureActions() {
        baseUrlView.doAfterTextChanged { text ->
            if (suppressConfigWatchers) return@doAfterTextChanged
            val currentHost = runCatching {
                OpenAiEndpoint.parse(text?.toString().orEmpty()).consentIdentity
            }.getOrNull()
            if (currentHost == null || currentHost != savedUserConsentHost) {
                consentView.isChecked = false
            }
            invalidateModelCatalog()
        }
        apiKeyView.doAfterTextChanged {
            if (!suppressConfigWatchers) invalidateModelCatalog()
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                updateActionState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateActionState()
            }
        }
        fetchModelsButton.setOnClickListener { fetchModels() }
        saveButton.setOnClickListener {
            saveConfiguration().fold(
                onSuccess = { config ->
                    resultView.text = getString(
                        R.string.online_save_success,
                        activeRequestUrl(config),
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
            cancelModelFetch()
            testGeneration += 1L
            testEngine?.close()
            testEngine = null
            repository.deleteApiKey()
            suppressConfigWatchers = true
            apiKeyView.text.clear()
            suppressConfigWatchers = false
            invalidateModelCatalog()
            refreshKeyStatus()
            resultView.setText(R.string.online_key_deleted)
            setResult(Activity.RESULT_OK)
        }
    }

    private fun fetchModels() {
        val requestConfig = runCatching {
            val endpoint = OpenAiEndpoint.parse(baseUrlView.text.toString())
            require(consentView.isChecked) {
                getString(R.string.online_consent_required)
            }
            val apiKey = repository.resolveApiKey(apiKeyView.text.toString())
            endpoint to apiKey
        }.getOrElse { error ->
            showModelFetchFailure(error)
            return
        }
        val (endpoint, apiKey) = requestConfig
        cancelModelFetch()
        val generation = modelFetchGeneration
        fetchingModels = true
        updateActionState()
        modelStatusView.text = getString(
            R.string.online_models_loading,
            endpoint.modelsUrl,
        )
        val client = OnlineModelCatalogClient(
            callFactory = modelHttpClient,
            endpoint = endpoint,
            apiKey = apiKey,
        )
        modelFetchCall = client.fetchModels { result ->
            runOnUiThread {
                finishModelFetch(generation, endpoint, result)
            }
        }
    }

    private fun finishModelFetch(
        generation: Long,
        endpoint: OpenAiEndpoint,
        result: Result<List<String>>,
    ) {
        if (generation != modelFetchGeneration || isDestroyed) return
        modelFetchCall = TranslationCall.NONE
        fetchingModels = false
        result.fold(
            onSuccess = { availableModels ->
                val savedConfig = repository.load()
                val preferredModel = savedConfig.modelId.takeIf {
                    savedConfig.baseUrl == endpoint.baseUrl && it in availableModels
                }
                setModelOptions(availableModels, preferredModel)
                modelCatalogBaseUrl = endpoint.baseUrl
                modelStatusView.text = getString(
                    R.string.online_models_loaded,
                    availableModels.size,
                )
            },
            onFailure = { error ->
                setModelOptions(emptyList(), null)
                modelCatalogBaseUrl = ""
                modelStatusView.text = getString(
                    R.string.online_models_failed,
                    error.localizedMessage ?: error.javaClass.simpleName,
                )
            },
        )
        updateActionState()
    }

    private fun saveConfiguration(): Result<OnlineTranslationConfig> = runCatching {
        val endpoint = OpenAiEndpoint.parse(baseUrlView.text.toString())
        require(consentView.isChecked) {
            getString(R.string.online_consent_required)
        }
        require(modelCatalogBaseUrl == endpoint.baseUrl) {
            getString(R.string.online_models_refresh_required)
        }
        val selectedModel = selectedModelId()
            ?: throw IllegalArgumentException(getString(R.string.online_model_selection_required))
        require(selectedModel in modelIds) {
            getString(R.string.online_model_selection_required)
        }
        val config = repository.save(
            baseUrl = endpoint.baseUrl,
            modelId = selectedModel,
            newApiKey = apiKeyView.text.toString().takeIf { it.isNotBlank() },
            consentAccepted = consentView.isChecked,
        )
        savedUserConsentHost = config.consentHost
        consentView.isChecked = true
        suppressConfigWatchers = true
        apiKeyView.text.clear()
        suppressConfigWatchers = false
        modelCatalogBaseUrl = config.baseUrl
        refreshKeyStatus()
        updateActionState()
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
        testingTranslation = true
        updateActionState()
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
        testingTranslation = false
        updateActionState()
        result.fold(
            onSuccess = { translated ->
                resultView.text = getString(R.string.online_test_success, translated)
            },
            onFailure = ::showFailure,
        )
    }

    private fun invalidateModelCatalog() {
        cancelModelFetch()
        setModelOptions(emptyList(), null)
        modelCatalogBaseUrl = ""
        modelStatusView.setText(R.string.online_models_not_loaded)
        updateActionState()
    }

    private fun cancelModelFetch() {
        modelFetchGeneration += 1L
        modelFetchCall.cancel()
        modelFetchCall = TranslationCall.NONE
        fetchingModels = false
    }

    private fun setModelOptions(options: List<String>, preferredModel: String?) {
        modelIds.clear()
        modelIds.addAll(options)
        modelDisplayItems.clear()
        modelDisplayItems += getString(R.string.online_model_selection_placeholder)
        modelDisplayItems.addAll(modelIds)
        modelAdapter.notifyDataSetChanged()
        val selectedIndex = preferredModel
            ?.let(modelIds::indexOf)
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        modelSpinner.setSelection(selectedIndex)
    }

    private fun selectedModelId(): String? =
        modelIds.getOrNull(modelSpinner.selectedItemPosition - 1)

    private fun updateActionState() {
        val busy = fetchingModels || testingTranslation
        val hasModel = selectedModelId() != null
        baseUrlView.isEnabled = !busy
        apiKeyView.isEnabled = !busy
        consentView.isEnabled = !busy
        fetchModelsButton.isEnabled = !busy
        modelSpinner.isEnabled = !busy && modelIds.isNotEmpty()
        saveButton.isEnabled = !busy && hasModel
        saveAndTestButton.isEnabled = !busy && hasModel
        deleteKeyButton.isEnabled = !busy
        testTextView.isEnabled = !busy
    }

    private fun hasCurrentConsent(): Boolean {
        val config = repository.load()
        val currentHost = runCatching {
            OpenAiEndpoint.parse(baseUrlView.text.toString()).consentIdentity
        }.getOrNull()
        return config.consentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION &&
            currentHost != null && currentHost == savedUserConsentHost
    }

    private fun activeRequestUrl(config: OnlineTranslationConfig): String =
        OpenAiEndpoint.parse(config.baseUrl).requestUrl.toString()

    private fun refreshKeyStatus() {
        keyStatusView.setText(
            if (repository.hasApiKey()) {
                R.string.online_key_saved
            } else {
                R.string.online_key_missing
            },
        )
    }

    private fun showModelFetchFailure(error: Throwable) {
        fetchingModels = false
        updateActionState()
        refreshKeyStatus()
        modelStatusView.text = getString(
            R.string.online_models_failed,
            error.localizedMessage ?: error.javaClass.simpleName,
        )
    }

    private fun showFailure(error: Throwable) {
        testingTranslation = false
        updateActionState()
        refreshKeyStatus()
        resultView.text = getString(
            R.string.online_test_failed,
            error.localizedMessage ?: error.javaClass.simpleName,
        )
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
