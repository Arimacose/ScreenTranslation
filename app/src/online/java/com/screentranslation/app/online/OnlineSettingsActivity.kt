package com.screentranslation.app.online

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.screentranslation.app.R
import com.screentranslation.app.ml.OnlineLlmTranslationEngine
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationCall
import com.screentranslation.app.prefs.AppPreferences
import okhttp3.OkHttpClient

class OnlineSettingsActivity : AppCompatActivity() {
    private lateinit var repository: OnlineTranslationConfigRepository
    private lateinit var preferences: AppPreferences
    private lateinit var modelHttpClient: OkHttpClient
    private lateinit var providerGroup: RadioGroup
    private lateinit var userApiContainer: View
    private lateinit var managedStatusView: TextView
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
    private var savedManagedConsentHost = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_online_settings)
        repository = OnlineTranslationConfigRepository(this)
        preferences = AppPreferences(this)
        modelHttpClient = OnlineHttpClientFactory.create()
        bindViews()
        loadConfiguration()
        configureActions()
    }

    override fun onDestroy() {
        cancelModelFetch()
        testGeneration += 1L
        testEngine?.close()
        testEngine = null
        modelHttpClient.dispatcher.executorService.shutdown()
        modelHttpClient.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun bindViews() {
        providerGroup = findViewById(R.id.radio_group_online_provider)
        userApiContainer = findViewById(R.id.container_online_user_api)
        managedStatusView = findViewById(R.id.text_online_managed_status)
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
        savedManagedConsentHost = config.managedConsentHost
        providerGroup.check(
            if (config.providerMode == OnlineProviderMode.MANAGED_CLOUD) {
                R.id.radio_online_managed_cloud
            } else {
                R.id.radio_online_user_api
            },
        )
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
        consentView.isChecked = hasCurrentConsent(selectedProviderMode())
        applyProviderModeUi()
        refreshKeyStatus()
        updateActionState()
    }

    private fun configureActions() {
        baseUrlView.doAfterTextChanged { text ->
            if (suppressConfigWatchers) return@doAfterTextChanged
            val currentHost = runCatching {
                OpenAiEndpoint.parse(text?.toString().orEmpty()).consentIdentity
            }.getOrNull()
            if (
                selectedProviderMode() == OnlineProviderMode.USER_API &&
                (currentHost == null || currentHost != savedUserConsentHost)
            ) {
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
        providerGroup.setOnCheckedChangeListener { _, _ ->
            consentView.isChecked = hasCurrentConsent(selectedProviderMode())
            applyProviderModeUi()
            updateActionState()
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
            require(selectedProviderMode() == OnlineProviderMode.USER_API) {
                "Model catalog is only used by the user API mode"
            }
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
        if (selectedProviderMode() == OnlineProviderMode.MANAGED_CLOUD) {
            require(preferences.targetLanguage.equals("zh", ignoreCase = true)) {
                getString(R.string.online_managed_target_required)
            }
            require(consentView.isChecked) {
                getString(R.string.online_managed_consent_required)
            }
            val config = repository.saveManagedCloud(
                consentAccepted = consentView.isChecked,
            )
            savedManagedConsentHost = config.managedConsentHost
            consentView.isChecked = true
            updateActionState()
            setResult(Activity.RESULT_OK)
            return@runCatching config
        }
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
        val providerMode = selectedProviderMode()
        val managedMode = providerMode == OnlineProviderMode.MANAGED_CLOUD
        val hasModel = selectedModelId() != null
        val managedReady = ManagedCloudService.isConfigured &&
            preferences.targetLanguage.equals("zh", ignoreCase = true)
        for (index in 0 until providerGroup.childCount) {
            providerGroup.getChildAt(index).isEnabled = !busy
        }
        baseUrlView.isEnabled = !busy
        apiKeyView.isEnabled = !busy
        consentView.isEnabled = !busy
        fetchModelsButton.isEnabled = !busy && !managedMode
        modelSpinner.isEnabled = !busy && modelIds.isNotEmpty()
        val selectedModeReady = if (managedMode) managedReady else hasModel
        saveButton.isEnabled = !busy && selectedModeReady
        saveAndTestButton.isEnabled = !busy && selectedModeReady
        deleteKeyButton.isEnabled = !busy && !managedMode
        testTextView.isEnabled = !busy
    }

    private fun selectedProviderMode(): OnlineProviderMode =
        if (providerGroup.checkedRadioButtonId == R.id.radio_online_user_api) {
            OnlineProviderMode.USER_API
        } else {
            OnlineProviderMode.MANAGED_CLOUD
        }

    private fun hasCurrentConsent(providerMode: OnlineProviderMode): Boolean {
        val config = repository.load()
        return when (providerMode) {
            OnlineProviderMode.USER_API -> {
                val currentHost = runCatching {
                    OpenAiEndpoint.parse(baseUrlView.text.toString()).consentIdentity
                }.getOrNull()
                config.consentVersion == OnlineTranslationConfig.CURRENT_CONSENT_VERSION &&
                    currentHost != null && currentHost == savedUserConsentHost
            }
            OnlineProviderMode.MANAGED_CLOUD -> {
                val currentHost = runCatching {
                    ManagedCloudService.endpoint().consentIdentity
                }.getOrNull()
                config.managedConsentVersion ==
                    OnlineTranslationConfig.CURRENT_CONSENT_VERSION &&
                    currentHost != null && currentHost == savedManagedConsentHost
            }
        }
    }

    private fun applyProviderModeUi() {
        val managedMode = selectedProviderMode() == OnlineProviderMode.MANAGED_CLOUD
        userApiContainer.visibility = if (managedMode) View.GONE else View.VISIBLE
        deleteKeyButton.visibility = if (managedMode) View.GONE else View.VISIBLE
        managedStatusView.visibility = if (managedMode) View.VISIBLE else View.GONE
        consentView.setText(
            if (managedMode) {
                R.string.online_managed_data_flow_consent
            } else {
                R.string.online_user_data_flow_consent
            },
        )
        if (managedMode) {
            if (ManagedCloudService.isConfigured) {
                val host = ManagedCloudService.endpoint().host
                managedStatusView.text = getString(
                    R.string.online_managed_configured,
                    host,
                )
            } else {
                managedStatusView.setText(R.string.online_managed_not_configured)
            }
        }
    }

    private fun activeRequestUrl(config: OnlineTranslationConfig): String =
        when (config.providerMode) {
            OnlineProviderMode.MANAGED_CLOUD -> ManagedCloudService.endpoint().requestUrl.toString()
            OnlineProviderMode.USER_API -> OpenAiEndpoint.parse(config.baseUrl).requestUrl.toString()
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
}
