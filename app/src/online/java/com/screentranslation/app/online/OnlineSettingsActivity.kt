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
import androidx.core.view.ViewCompat
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
    private lateinit var modelSearchView: EditText
    private lateinit var modelAdapter: ArrayAdapter<String>
    private lateinit var modelStatusView: TextView
    private lateinit var apiKeyView: EditText
    private lateinit var keyStatusView: TextView
    private lateinit var consentView: CheckBox
    private lateinit var fetchModelsButton: Button
    private lateinit var cancelModelFetchButton: Button
    private lateinit var saveButton: Button
    private lateinit var testTextView: EditText
    private lateinit var saveAndTestButton: Button
    private lateinit var cancelTestButton: Button
    private lateinit var deleteKeyButton: Button
    private lateinit var resultView: TextView
    private lateinit var detailsView: TextView

    private val allModels = mutableListOf<OnlineModelDescriptor>()
    private val modelIds = mutableListOf<String>()
    private val modelDisplayItems = mutableListOf<String>()
    private var modelCatalogBaseUrl = ""
    private var modelFetchCall: TranslationCall = TranslationCall.NONE
    private var modelFetchGeneration = 0L
    private var fetchingModels = false
    private var testingTranslation = false
    private var suppressConfigWatchers = false
    private var testEngine: TranslationBackend? = null
    private var testCall: TranslationCall = TranslationCall.NONE
    private var testGeneration = 0L
    private var testStartedAt = 0L
    private var latestMetric: OnlineRequestMetric? = null
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
        ViewCompat.setAccessibilityPaneTitle(
            findViewById(android.R.id.content),
            getString(R.string.online_settings_title),
        )
        applySystemBarInsets()
        loadConfiguration()
        configureActions()
    }

    override fun onDestroy() {
        cancelModelFetch()
        cancelTranslationTest(showOutcome = false)
        OnlineHttpClientFactory.closeAsync(modelHttpClient)
        super.onDestroy()
    }

    private fun bindViews() {
        baseUrlView = findViewById(R.id.edit_online_base_url)
        modelSpinner = findViewById(R.id.spinner_online_model)
        modelSearchView = findViewById(R.id.edit_online_model_search)
        modelStatusView = findViewById(R.id.text_online_model_status)
        apiKeyView = findViewById(R.id.edit_online_api_key)
        keyStatusView = findViewById(R.id.text_online_key_status)
        consentView = findViewById(R.id.check_online_consent)
        fetchModelsButton = findViewById(R.id.button_online_fetch_models)
        cancelModelFetchButton = findViewById(R.id.button_online_cancel_fetch)
        saveButton = findViewById(R.id.button_online_save)
        testTextView = findViewById(R.id.edit_online_test_text)
        saveAndTestButton = findViewById(R.id.button_online_save_test)
        cancelTestButton = findViewById(R.id.button_online_cancel_test)
        deleteKeyButton = findViewById(R.id.button_online_delete_key)
        resultView = findViewById(R.id.text_online_result)
        detailsView = findViewById(R.id.text_online_details)
        listOf(modelStatusView, keyStatusView, resultView).forEach { statusView ->
            statusView.doAfterTextChanged { text ->
                ViewCompat.setStateDescription(statusView, text)
            }
        }
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
            setModelOptions(listOf(OnlineModelDescriptor(config.modelId)), config.modelId)
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
        modelSearchView.doAfterTextChanged { applyModelFilter(it?.toString().orEmpty()) }
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
        cancelModelFetchButton.setOnClickListener {
            cancelModelFetch(showOutcome = true)
        }
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
        cancelTestButton.setOnClickListener { cancelTranslationTest(showOutcome = true) }
        resultView.setOnClickListener {
            if (detailsView.text.isNotBlank()) {
                detailsView.visibility = if (detailsView.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }
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
        result: Result<List<OnlineModelDescriptor>>,
    ) {
        if (generation != modelFetchGeneration || isDestroyed) return
        modelFetchCall = TranslationCall.NONE
        fetchingModels = false
        result.fold(
            onSuccess = { availableModels ->
                val savedConfig = repository.load()
                val preferredModel = savedConfig.modelId.takeIf {
                    savedConfig.baseUrl == endpoint.baseUrl &&
                        availableModels.any { model -> model.id == it }
                }
                setModelOptions(availableModels, preferredModel)
                modelCatalogBaseUrl = endpoint.baseUrl
                modelStatusView.text = getString(
                    R.string.online_models_loaded,
                    availableModels.size,
                ) + "\n/models: ${endpoint.modelsUrl}\n/chat/completions: ${endpoint.requestUrl}"
            },
            onFailure = { error ->
                setModelOptions(emptyList(), null)
                modelCatalogBaseUrl = ""
                modelStatusView.text = getString(
                    R.string.online_models_failed,
                    OnlineFailureMapper.map(error).summary,
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
        cancelTranslationTest(showOutcome = false)
        val generation = ++testGeneration
        latestMetric = null
        testStartedAt = android.os.SystemClock.elapsedRealtime()
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
        (engine as? OnlineLlmTranslationEngine)?.observeMetrics { metric ->
            latestMetric = metric
        }
        testEngine = engine
        testingTranslation = true
        updateActionState()
        resultView.setText(R.string.online_test_running)
        engine.prepare(warmRuntime = false) { preparation ->
            preparation.fold(
                onSuccess = {
                    testCall = engine.translate(sourceText) { translation ->
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
        testCall = TranslationCall.NONE
        engine.close()
        testingTranslation = false
        updateActionState()
        result.fold(
            onSuccess = { translated ->
                val metric = latestMetric
                val elapsed = metric?.latencyMillis
                    ?: (android.os.SystemClock.elapsedRealtime() - testStartedAt).coerceAtLeast(0L)
                resultView.text = getString(R.string.online_test_success, translated) +
                    "\n\n模型：${selectedModelId().orEmpty()} · ${elapsed} ms · HTTP ${metric?.httpStatus ?: "—"}" +
                    "\n输入/输出：${metric?.inputCharacters ?: testTextView.text.length}/" +
                    "${metric?.outputCharacters ?: translated.length} 字符 · token " +
                    "${metric?.promptTokens ?: "—"}/${metric?.completionTokens ?: "—"}"
                detailsView.text = "请求状态：${metric?.outcome ?: OnlineRequestOutcome.SUCCEEDED}；" +
                    "尝试次数：${metric?.attempts ?: 1}。点击上方结果可收起详情。"
                detailsView.visibility = View.VISIBLE
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

    private fun cancelModelFetch(showOutcome: Boolean = false) {
        val wasFetching = fetchingModels
        modelFetchGeneration += 1L
        modelFetchCall.cancel()
        modelFetchCall = TranslationCall.NONE
        fetchingModels = false
        if (showOutcome && wasFetching) {
            modelStatusView.text = OnlineFailureMapper.map(
                java.util.concurrent.CancellationException("Model fetch cancelled"),
            ).summary
        }
        updateActionState()
    }

    private fun setModelOptions(
        options: List<OnlineModelDescriptor>,
        preferredModel: String?,
    ) {
        allModels.clear()
        allModels.addAll(options)
        applyModelFilter(modelSearchView.text?.toString().orEmpty(), preferredModel)
    }

    private fun applyModelFilter(query: String, preferredModel: String? = selectedModelId()) {
        val options = OnlineModelSearchIndex.filter(allModels, query)
        modelIds.clear()
        modelIds.addAll(options.map { it.id })
        modelDisplayItems.clear()
        modelDisplayItems += getString(R.string.online_model_selection_placeholder)
        modelDisplayItems.addAll(options.map { it.label })
        modelAdapter.notifyDataSetChanged()
        val selectedIndex = preferredModel
            ?.let(modelIds::indexOf)
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        modelSpinner.setSelection(selectedIndex)
        updateActionState()
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
        cancelModelFetchButton.isEnabled = fetchingModels
        cancelModelFetchButton.visibility = if (fetchingModels) View.VISIBLE else View.GONE
        modelSearchView.isEnabled = !busy && allModels.isNotEmpty()
        modelSpinner.isEnabled = !busy && modelIds.isNotEmpty()
        saveButton.isEnabled = !busy && hasModel
        saveAndTestButton.isEnabled = !busy && hasModel
        cancelTestButton.isEnabled = testingTranslation
        cancelTestButton.visibility = if (testingTranslation) View.VISIBLE else View.GONE
        deleteKeyButton.isEnabled = !busy && repository.hasApiKey()
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
            OnlineFailureMapper.map(error).summary,
        )
    }

    private fun showFailure(error: Throwable) {
        testingTranslation = false
        updateActionState()
        refreshKeyStatus()
        val failure = OnlineFailureMapper.map(error)
        resultView.text = getString(
            R.string.online_test_failed,
            failure.summary,
        ) + "\n" + getString(R.string.online_details_affordance)
        detailsView.text = "${failure.technicalCode}\n${failure.redactedDetail}"
        detailsView.visibility = View.GONE
    }

    private fun cancelTranslationTest(showOutcome: Boolean) {
        val wasTesting = testingTranslation
        testGeneration += 1L
        testCall.cancel()
        testCall = TranslationCall.NONE
        testEngine?.close()
        testEngine = null
        testingTranslation = false
        if (showOutcome && wasTesting) {
            showFailure(java.util.concurrent.CancellationException("Translation test cancelled"))
        } else {
            updateActionState()
        }
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
