package com.screentranslation.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.ModelPreparationStage
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationBackendFactory
import com.screentranslation.app.model.LanguageOption
import com.screentranslation.app.model.CaptureMode
import com.screentranslation.app.model.UiStyle
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.service.CapturePermissionPreconditions
import com.screentranslation.app.service.CapturePermissionStep
import com.screentranslation.app.service.CaptureShortcutNotification
import com.screentranslation.app.service.ScreenTranslationService
import com.screentranslation.app.ui.UiStyleController
import com.screentranslation.app.vendor.HyperOsVendorAdapter

internal enum class BatteryPolicyUiState {
    BACKGROUND_RESTRICTED,
    HYPER_OS_UNRESTRICTED,
    HYPER_OS_NOT_UNRESTRICTED,
    AOSP_POWER_ALLOWLISTED,
    VENDOR_POLICY_UNVERIFIED,
}

/**
 * Android exposes background restriction and the AOSP power allowlist as
 * separate signals. HyperOS' per-app "Unrestricted" policy is vendor-owned and
 * does not necessarily add the package to the AOSP allowlist.
 */
internal fun resolveBatteryPolicyUiState(
    isBackgroundRestricted: Boolean?,
    isAospPowerAllowlisted: Boolean?,
    isHyperOsUnrestricted: Boolean? = null,
): BatteryPolicyUiState = when {
    isBackgroundRestricted == true -> BatteryPolicyUiState.BACKGROUND_RESTRICTED
    isHyperOsUnrestricted == true -> BatteryPolicyUiState.HYPER_OS_UNRESTRICTED
    isHyperOsUnrestricted == false -> BatteryPolicyUiState.HYPER_OS_NOT_UNRESTRICTED
    isAospPowerAllowlisted == true -> BatteryPolicyUiState.AOSP_POWER_ALLOWLISTED
    else -> BatteryPolicyUiState.VENDOR_POLICY_UNVERIFIED
}

internal fun sourceOptionsForEdition(
    isBergamotLite: Boolean,
    targetsChineseOnly: Boolean,
): List<LanguageOption> = when {
    isBergamotLite -> listOf(
        LanguageOption.ENGLISH,
        LanguageOption.JAPANESE,
    )
    targetsChineseOnly ->
        LanguageOption.sourceOptions.filterNot {
            it == LanguageOption.CHINESE_SIMPLIFIED
        }
    else -> LanguageOption.sourceOptions
}

internal fun targetOptionsForEdition(
    targetsChineseOnly: Boolean,
): List<LanguageOption> =
    if (targetsChineseOnly) {
        listOf(LanguageOption.CHINESE_SIMPLIFIED)
    } else {
        LanguageOption.targetOptions
    }

internal data class ModelPreparationButtonState(
    val isReady: Boolean,
    val isEnabled: Boolean,
)

internal fun resolveModelPreparationButtonState(
    serviceRunning: Boolean,
    operationIdle: Boolean,
    sameLanguage: Boolean,
    readyForSelectedPair: Boolean,
): ModelPreparationButtonState = ModelPreparationButtonState(
    isReady = readyForSelectedPair,
    isEnabled = !serviceRunning && operationIdle && !sameLanguage && !readyForSelectedPair,
)

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var projectionManager: MediaProjectionManager

    private lateinit var experimentalBannerView: TextView
    private lateinit var uiStyleGroup: MaterialButtonToggleGroup
    private lateinit var materialMonetSwitch: MaterialSwitch
    private lateinit var materialMonetHintView: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var captureModeSpinner: Spinner
    private lateinit var captureModeHintView: TextView
    private lateinit var intervalSeekBar: SeekBar
    private lateinit var intervalValueView: TextView
    private lateinit var prepareModelsButton: Button
    private lateinit var manageModelsButton: Button
    private lateinit var modelStatusView: TextView
    private lateinit var onlineSettingsButton: Button
    private lateinit var onlineConfigStatusView: TextView
    private lateinit var experimentalSmokeTestButton: Button
    private lateinit var experimentalSmokeTestResultView: TextView
    private lateinit var notificationPermissionButton: Button
    private lateinit var notificationPermissionStatusView: TextView
    private lateinit var overlayPermissionButton: Button
    private lateinit var overlayPermissionStatusView: TextView
    private lateinit var batteryPolicyButton: Button
    private lateinit var batteryPolicyStatusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var serviceStatusView: TextView

    private val targetsChineseOnly =
        BuildConfig.BERGAMOT_LITE || BuildConfig.HYMT2_Q4_EXPERIMENTAL
    private val availableSourceOptions: List<LanguageOption> =
        sourceOptionsForEdition(
            isBergamotLite = BuildConfig.BERGAMOT_LITE,
            targetsChineseOnly = targetsChineseOnly,
        )
    private val availableTargetOptions: List<LanguageOption> =
        targetOptionsForEdition(targetsChineseOnly)

    private var modelPreparationEngine: TranslationBackend? = null
    private var modelPreparationGeneration = 0
    private var modelReadyFor: Pair<String, String>? = null
    private var experimentalSmokeTestEngine: TranslationBackend? = null
    private var experimentalSmokeTestGeneration = 0
    private var languageListenersReady = false
    private var pendingStartAfterNotificationPermission = false
    private var pendingStartAfterOverlayPermission = false
    private var projectionRequestInFlight = false
    private var sessionStateReceiverRegistered = false

    private val sessionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenTranslationService.ACTION_SESSION_STATE_CHANGED) {
                refreshServiceStatus()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissionState()
        if (pendingStartAfterNotificationPermission) {
            pendingStartAfterNotificationPermission = false
            continueStartAfterNotificationPermission()
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshPermissionState()
        maybeContinueAfterOverlayPermission()
    }

    private val onlineSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        invalidatePreparedModel()
        refreshOnlineConfigurationStatus()
    }

    private val modelManagementLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                invalidatePreparedModel()
                prepareCurrentModels()
            }
            ModelManagementActivity.RESULT_MODELS_CHANGED -> invalidatePreparedModel()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        projectionRequestInFlight = false
        val projectionData = result.data
        if (result.resultCode != Activity.RESULT_OK || projectionData == null) {
            serviceStatusView.setText(R.string.capture_denied)
            setServiceRunningUi(false)
            return@registerForActivityResult
        }

        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        val interval = selectedFrameInterval()
        preferences.save(
            source.languageTag,
            target.languageTag,
            interval,
            selectedCaptureMode(),
        )

        try {
            val serviceIntent = ScreenTranslationService.startIntent(
                context = this,
                resultCode = result.resultCode,
                resultData = projectionData,
                sourceLanguage = source.languageTag,
                targetLanguage = target.languageTag,
                frameIntervalMs = interval,
                captureMode = selectedCaptureMode(),
            )
            ContextCompat.startForegroundService(this, serviceIntent)
            serviceStatusView.setText(R.string.service_starting)
            setServiceRunningUi(true)
        } catch (error: Exception) {
            ScreenTranslationService.discardPendingStartRequest()
            serviceStatusView.text = getString(
                R.string.start_failed,
                error.localizedMessage ?: error.javaClass.simpleName,
            )
            setServiceRunningUi(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val uiStyle = UiStyleController.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(
            if (uiStyle == UiStyle.APPLE) {
                R.layout.activity_main_apple
            } else {
                R.layout.activity_main
            },
        )

        preferences = AppPreferences(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        bindViews()
        applySystemBarInsets()
        configureAppearance()
        configureLanguageSelectors()
        configureCaptureMode()
        configureFrameInterval()
        configureActions()
        modelReadyFor = savedInstanceState?.let { state ->
            val source = state.getString(STATE_READY_SOURCE)
            val target = state.getString(STATE_READY_TARGET)
            if (source != null && target != null) source to target else null
        }
        refreshPermissionState()
        setServiceRunningUi(false)
        refreshOnlineConfigurationStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        modelReadyFor?.let { (source, target) ->
            outState.putString(STATE_READY_SOURCE, source)
            outState.putString(STATE_READY_TARGET, target)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshServiceStatus()
    }

    override fun onStart() {
        super.onStart()
        if (!sessionStateReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                sessionStateReceiver,
                IntentFilter(ScreenTranslationService.ACTION_SESSION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            sessionStateReceiverRegistered = true
        }
        if (::serviceStatusView.isInitialized) refreshServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::overlayPermissionStatusView.isInitialized) {
            refreshPermissionState()
            maybeContinueAfterOverlayPermission()
            refreshServiceStatus()
            refreshOnlineConfigurationStatus()
        }
    }

    override fun onDestroy() {
        modelPreparationGeneration += 1
        modelPreparationEngine?.close()
        modelPreparationEngine = null
        experimentalSmokeTestGeneration += 1
        experimentalSmokeTestEngine?.close()
        experimentalSmokeTestEngine = null
        super.onDestroy()
    }

    override fun onStop() {
        if (sessionStateReceiverRegistered) {
            unregisterReceiver(sessionStateReceiver)
            sessionStateReceiverRegistered = false
        }
        super.onStop()
    }

    private fun bindViews() {
        experimentalBannerView = findViewById(R.id.text_experimental_banner)
        experimentalBannerView.visibility = if (
            BuildConfig.HYMT2_Q4_EXPERIMENTAL || BuildConfig.ONLINE_LLM
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        uiStyleGroup = findViewById(R.id.group_ui_style)
        materialMonetSwitch = findViewById(R.id.switch_material_monet)
        materialMonetHintView = findViewById(R.id.text_material_monet_hint)
        sourceSpinner = findViewById(R.id.spinner_source_language)
        targetSpinner = findViewById(R.id.spinner_target_language)
        captureModeSpinner = findViewById(R.id.spinner_capture_mode)
        captureModeHintView = findViewById(R.id.text_capture_mode_hint)
        intervalSeekBar = findViewById(R.id.seek_frame_interval)
        intervalValueView = findViewById(R.id.text_frame_interval)
        prepareModelsButton = findViewById(R.id.button_prepare_models)
        manageModelsButton = findViewById(R.id.button_manage_models)
        modelStatusView = findViewById(R.id.text_model_status)
        onlineSettingsButton = findViewById(R.id.button_online_settings)
        onlineConfigStatusView = findViewById(R.id.text_online_config_status)
        experimentalSmokeTestButton = findViewById(R.id.button_experimental_smoke_test)
        experimentalSmokeTestResultView =
            findViewById(R.id.text_experimental_smoke_test_result)
        val experimentalVisibility = if (BuildConfig.HYMT2_Q4_EXPERIMENTAL) {
            View.VISIBLE
        } else {
            View.GONE
        }
        experimentalSmokeTestButton.visibility = experimentalVisibility
        experimentalSmokeTestResultView.visibility = experimentalVisibility
        val onlineVisibility = if (BuildConfig.ONLINE_LLM) View.VISIBLE else View.GONE
        onlineSettingsButton.visibility = onlineVisibility
        onlineConfigStatusView.visibility = onlineVisibility
        notificationPermissionButton = findViewById(R.id.button_notification_permission)
        notificationPermissionStatusView =
            findViewById(R.id.text_notification_permission_status)
        overlayPermissionButton = findViewById(R.id.button_overlay_permission)
        overlayPermissionStatusView = findViewById(R.id.text_overlay_permission_status)
        batteryPolicyButton = findViewById(R.id.button_battery_policy)
        batteryPolicyStatusView = findViewById(R.id.text_battery_policy_status)
        startButton = findViewById(R.id.button_start)
        stopButton = findViewById(R.id.button_stop)
        serviceStatusView = findViewById(R.id.text_service_status)
    }

    private fun configureAppearance() {
        val currentStyle = preferences.uiStyle
        uiStyleGroup.check(
            when (currentStyle) {
                UiStyle.APPLE -> R.id.button_style_apple
                UiStyle.MIUIX -> R.id.button_style_miuix
                UiStyle.MATERIAL3 -> R.id.button_style_material3
            },
        )
        val materialOnlyVisibility = if (currentStyle == UiStyle.MATERIAL3) {
            View.VISIBLE
        } else {
            View.GONE
        }
        materialMonetSwitch.visibility = materialOnlyVisibility
        materialMonetHintView.visibility = materialOnlyVisibility
        materialMonetSwitch.isChecked = preferences.materialMonetEnabled
        val dynamicColorsAvailable = DynamicColors.isDynamicColorAvailable()
        materialMonetSwitch.isEnabled = dynamicColorsAvailable
        materialMonetHintView.setText(
            if (dynamicColorsAvailable) {
                R.string.material_monet_hint
            } else {
                R.string.material_monet_unavailable
            },
        )

        uiStyleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selectedStyle = when (checkedId) {
                R.id.button_style_apple -> UiStyle.APPLE
                R.id.button_style_miuix -> UiStyle.MIUIX
                R.id.button_style_material3 -> UiStyle.MATERIAL3
                else -> return@addOnButtonCheckedListener
            }
            if (selectedStyle != preferences.uiStyle) {
                preferences.uiStyle = selectedStyle
                recreate()
            }
        }
        materialMonetSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled != preferences.materialMonetEnabled) {
                preferences.materialMonetEnabled = enabled
                recreate()
            }
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

    private fun configureLanguageSelectors() {
        val sourceOptions = availableSourceOptions
        val targetOptions = availableTargetOptions
        val sourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sourceOptions.map { it.displayName(this) },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val targetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            targetOptions.map { it.displayName(this) },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sourceSpinner.adapter = sourceAdapter
        targetSpinner.adapter = targetAdapter

        val source = LanguageOption.fromLanguageTag(
            preferences.sourceLanguage,
            LanguageOption.defaultSource,
        )
        val target = LanguageOption.fromLanguageTag(
            preferences.targetLanguage,
            LanguageOption.defaultTarget,
        )
        sourceSpinner.setSelection(
            sourceOptions.indexOf(source).takeIf { it >= 0 }
                ?: sourceOptions.indexOf(LanguageOption.defaultSource),
            false,
        )
        targetSpinner.setSelection(
            targetOptions.indexOf(target).takeIf { it >= 0 }
                ?: targetOptions.indexOf(LanguageOption.defaultTarget),
            false,
        )

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!languageListenersReady) return
                persistCurrentConfiguration()
                invalidatePreparedModel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        sourceSpinner.onItemSelectedListener = listener
        targetSpinner.onItemSelectedListener = listener
        languageListenersReady = true
    }

    private fun configureFrameInterval() {
        intervalSeekBar.max = FRAME_INTERVAL_OPTIONS_MS.lastIndex
        val savedInterval = preferences.frameIntervalMs
        val closestIndex = FRAME_INTERVAL_OPTIONS_MS.indices.minByOrNull { index ->
            kotlin.math.abs(FRAME_INTERVAL_OPTIONS_MS[index] - savedInterval)
        } ?: DEFAULT_INTERVAL_INDEX
        intervalSeekBar.progress = closestIndex
        updateIntervalLabel()

        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean,
            ) {
                updateIntervalLabel()
                if (fromUser) persistCurrentConfiguration()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persistCurrentConfiguration()
            }
        })
    }

    private fun configureCaptureMode() {
        captureModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.capture_mode_region),
                getString(R.string.capture_mode_full_screen),
            ),
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        captureModeSpinner.setSelection(preferences.captureMode.ordinal, false)
        updateCaptureModeHint()
        captureModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                updateCaptureModeHint()
                persistCurrentConfiguration()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun configureActions() {
        prepareModelsButton.setOnClickListener {
            prepareCurrentModels()
        }
        manageModelsButton.setOnClickListener {
            modelManagementLauncher.launch(Intent(this, ModelManagementActivity::class.java))
        }
        experimentalSmokeTestButton.setOnClickListener {
            runExperimentalSmokeTest()
        }
        onlineSettingsButton.setOnClickListener {
            if (BuildConfig.ONLINE_LLM) {
                onlineSettingsLauncher.launch(
                    Intent().setClassName(this, ONLINE_SETTINGS_ACTIVITY_CLASS),
                )
            }
        }
        notificationPermissionButton.setOnClickListener {
            requestNotificationPermission()
        }
        overlayPermissionButton.setOnClickListener {
            openOverlayPermissionSettings()
        }
        batteryPolicyButton.setOnClickListener {
            openBatteryPolicySettings()
        }
        startButton.setOnClickListener {
            beginStartFlow()
        }
        stopButton.setOnClickListener {
            stopTranslationService()
        }
    }

    private fun beginStartFlow() {
        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        if (source == target) {
            modelStatusView.setText(R.string.model_same_language)
            serviceStatusView.setText(R.string.model_same_language)
            return
        }

        persistCurrentConfiguration()
        val selectedPair = source.languageTag to target.languageTag
        if (modelReadyFor != selectedPair) {
            prepareCurrentModels {
                continueStartAfterModelPreparation()
            }
        } else {
            continueStartAfterModelPreparation()
        }
    }

    private fun prepareCurrentModels(onReady: (() -> Unit)? = null) {
        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        if (source == target) {
            modelStatusView.setText(R.string.model_same_language)
            return
        }

        val requestedPair = source.languageTag to target.languageTag
        val generation = ++modelPreparationGeneration
        modelPreparationEngine?.close()
        val engine = TranslationBackendFactory.create(
            context = this,
            sourceLanguage = source.languageTag,
            targetLanguage = target.languageTag,
        )
        modelPreparationEngine = engine
        modelReadyFor = null
        prepareModelsButton.isEnabled = false
        startButton.isEnabled = false
        modelStatusView.text = getString(
            R.string.model_preparing,
            source.displayName(this),
            target.displayName(this),
        )

        engine.prepare(
            requireWifi = false,
            warmRuntime = false,
            onProgress = { progress ->
                runOnUiThread {
                    if (generation == modelPreparationGeneration && !isDestroyed) {
                        modelStatusView.text = modelPreparationStatus(progress)
                    }
                }
            },
        ) { result ->
            runOnUiThread {
                if (generation != modelPreparationGeneration || isDestroyed) {
                    engine.close()
                    return@runOnUiThread
                }
                if (modelPreparationEngine === engine) {
                    modelPreparationEngine = null
                }
                engine.close()
                setServiceRunningUi(ScreenTranslationService.isRunning)

                val currentPair = selectedSourceLanguage().languageTag to
                    selectedTargetLanguage().languageTag
                if (currentPair != requestedPair) {
                    modelStatusView.setText(R.string.model_not_prepared)
                    setServiceRunningUi(ScreenTranslationService.isRunning)
                    return@runOnUiThread
                }

                result.fold(
                    onSuccess = {
                        modelReadyFor = requestedPair
                        modelStatusView.text = getString(
                            R.string.model_ready,
                            source.displayName(this),
                            target.displayName(this),
                        )
                        setServiceRunningUi(ScreenTranslationService.isRunning)
                        onReady?.invoke()
                    },
                    onFailure = { error ->
                        modelReadyFor = null
                        modelStatusView.text = getString(
                            R.string.model_failed,
                            error.localizedMessage ?: getString(R.string.unknown_error),
                        )
                        setServiceRunningUi(ScreenTranslationService.isRunning)
                    },
                )
            }
        }
    }

    private fun modelPreparationStatus(progress: ModelPreparationProgress): String =
        when (progress.stage) {
            ModelPreparationStage.PREPARING -> getString(R.string.model_progress_preparing)
            ModelPreparationStage.VERIFYING -> getString(R.string.model_progress_verifying)
            ModelPreparationStage.LOADING_RUNTIME -> getString(R.string.model_progress_loading_runtime)
            ModelPreparationStage.DOWNLOADING -> {
                val completed = progress.completedBytes ?: 0L
                val total = progress.totalBytes ?: 0L
                val percent = if (total > 0L) {
                    ((completed * 100L) / total).coerceIn(0L, 100L)
                } else {
                    0L
                }
                getString(
                    R.string.model_progress_downloading,
                    Formatter.formatFileSize(this, completed),
                    Formatter.formatFileSize(this, total),
                    percent,
                )
            }
        }

    private fun runExperimentalSmokeTest() {
        if (!BuildConfig.HYMT2_Q4_EXPERIMENTAL) return
        if (ScreenTranslationService.isRunning) {
            experimentalSmokeTestResultView.setText(
                R.string.experimental_smoke_test_service_running,
            )
            setServiceRunningUi(true)
            return
        }

        val generation = ++experimentalSmokeTestGeneration
        experimentalSmokeTestEngine?.close()
        val engine = TranslationBackendFactory.create(
            context = this,
            sourceLanguage = EXPERIMENTAL_SMOKE_SOURCE_LANGUAGE,
            targetLanguage = EXPERIMENTAL_SMOKE_TARGET_LANGUAGE,
        )
        experimentalSmokeTestEngine = engine
        experimentalSmokeTestButton.isEnabled = false
        startButton.isEnabled = false
        experimentalSmokeTestResultView.setText(R.string.experimental_smoke_test_running)
        val startedAt = SystemClock.elapsedRealtime()

        engine.prepare(
            requireWifi = false,
            warmRuntime = true,
            onProgress = { progress ->
                runOnUiThread {
                    if (
                        generation == experimentalSmokeTestGeneration &&
                        !isDestroyed
                    ) {
                        experimentalSmokeTestResultView.text =
                            modelPreparationStatus(progress)
                    }
                }
            },
        ) { preparation ->
            preparation.fold(
                onSuccess = {
                    engine.translate(EXPERIMENTAL_SMOKE_SOURCE_TEXT) { translation ->
                        finishExperimentalSmokeTest(
                            generation = generation,
                            engine = engine,
                            startedAt = startedAt,
                            translation = translation,
                        )
                    }
                },
                onFailure = { error ->
                    finishExperimentalSmokeTest(
                        generation = generation,
                        engine = engine,
                        startedAt = startedAt,
                        translation = Result.failure(error),
                    )
                },
            )
        }
    }

    private fun finishExperimentalSmokeTest(
        generation: Int,
        engine: TranslationBackend,
        startedAt: Long,
        translation: Result<String>,
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        runOnUiThread {
            if (generation != experimentalSmokeTestGeneration || isDestroyed) {
                engine.close()
                return@runOnUiThread
            }
            if (experimentalSmokeTestEngine === engine) {
                experimentalSmokeTestEngine = null
            }
            engine.close()
            setServiceRunningUi(ScreenTranslationService.isRunning)
            translation.fold(
                onSuccess = { translatedText ->
                    Log.i(
                        EXPERIMENTAL_SMOKE_LOG_TAG,
                        "elapsedMs=$elapsedMs translation=$translatedText",
                    )
                    val smokePair = EXPERIMENTAL_SMOKE_SOURCE_LANGUAGE to
                        EXPERIMENTAL_SMOKE_TARGET_LANGUAGE
                    val currentPair = selectedSourceLanguage().languageTag to
                        selectedTargetLanguage().languageTag
                    if (currentPair == smokePair) {
                        modelReadyFor = smokePair
                        modelStatusView.text = getString(
                            R.string.model_ready,
                            selectedSourceLanguage().displayName(this),
                            selectedTargetLanguage().displayName(this),
                        )
                    }
                    experimentalSmokeTestResultView.text = getString(
                        R.string.experimental_smoke_test_success,
                        elapsedMs,
                        EXPERIMENTAL_SMOKE_SOURCE_TEXT,
                        translatedText,
                    )
                },
                onFailure = { error ->
                    Log.e(EXPERIMENTAL_SMOKE_LOG_TAG, "Self-test failed", error)
                    experimentalSmokeTestResultView.text = getString(
                        R.string.experimental_smoke_test_failed,
                        elapsedMs,
                        error.localizedMessage ?: getString(R.string.unknown_error),
                    )
                },
            )
        }
    }

    private fun continueStartAfterModelPreparation() {
        if (capturePermissionPreconditions().shouldRequestNotification) {
            pendingStartAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueStartAfterNotificationPermission()
    }

    private fun continueStartAfterNotificationPermission() {
        when (capturePermissionPreconditions().nextBlockingStep) {
            CapturePermissionStep.REQUEST_OVERLAY -> {
                pendingStartAfterOverlayPermission = true
                openOverlayPermissionSettings()
            }

            CapturePermissionStep.REQUEST_PROJECTION -> requestProjectionPermission()
        }
    }

    private fun capturePermissionPreconditions(): CapturePermissionPreconditions =
        CapturePermissionPreconditions(
            notificationGranted = hasNotificationPermission(),
            overlayGranted = Settings.canDrawOverlays(this),
        )

    private fun requestProjectionPermission() {
        if (projectionRequestInFlight) return
        projectionRequestInFlight = true
        serviceStatusView.setText(R.string.service_requesting_capture)

        // The flag is otherwise only cleared by the launcher callback. If the
        // launch throws, that callback never arrives and both the in-flight
        // guard above and the onResume status refresh stay stuck permanently.
        try {
            val captureIntent = projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
            projectionLauncher.launch(captureIntent)
        } catch (error: Exception) {
            projectionRequestInFlight = false
            serviceStatusView.text = getString(
                R.string.start_failed,
                error.localizedMessage ?: error.javaClass.simpleName,
            )
            setServiceRunningUi(false)
        }
    }

    private fun requestNotificationPermission() {
        if (!hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshPermissionState()
        }
    }

    private fun openOverlayPermissionSettings() {
        val packageUri = "package:$packageName".toUri()
        val candidates = HyperOsVendorAdapter.overlayPermissionIntents(packageName) + listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )

        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) == null) continue
            try {
                overlaySettingsLauncher.launch(intent)
                return
            } catch (_: Exception) {
                // Try the next ROM/system settings surface.
            }
        }
    }

    /**
     * HyperOS / MIUI enforce their own power policy on top of the AOSP battery
     * optimization whitelist, and an aggressive policy kills the capture
     * foreground service during long sessions. The vendor screen is tried first
     * and the AOSP surfaces are the fallback, mirroring the overlay-permission
     * chain above.
     *
     * The system dialog behind REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is
     * deliberately not used: Play Console only allows that permission for a
     * narrow set of app categories. Sending the user to the settings page is
     * the portable, policy-safe option.
     */
    private fun openBatteryPolicySettings() {
        val packageUri = "package:$packageName".toUri()
        val candidates = HyperOsVendorAdapter.batteryPolicyIntents(packageName) + listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) == null) continue
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Try the next ROM/system settings surface.
            }
        }
    }

    private fun batteryPolicyUiState(): BatteryPolicyUiState =
        resolveBatteryPolicyUiState(
            isBackgroundRestricted =
                getSystemService(ActivityManager::class.java)?.isBackgroundRestricted,
            isAospPowerAllowlisted =
                getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(packageName),
            isHyperOsUnrestricted = HyperOsVendorAdapter
                .isPowerPolicyUnrestricted(this, packageName),
        )

    private fun maybeContinueAfterOverlayPermission() {
        if (!pendingStartAfterOverlayPermission) return
        if (Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlayPermission = false
            requestProjectionPermission()
        }
    }

    private fun stopTranslationService() {
        try {
            startService(ScreenTranslationService.stopIntent(this))
            serviceStatusView.setText(R.string.service_stopped)
        } catch (error: Exception) {
            serviceStatusView.text = getString(
                R.string.start_failed,
                error.localizedMessage ?: error.javaClass.simpleName,
            )
        }
        setServiceRunningUi(false)
    }

    private fun refreshPermissionState() {
        val notificationGranted = hasNotificationPermission()
        notificationPermissionStatusView.setText(
            if (notificationGranted) {
                R.string.notification_granted
            } else {
                R.string.notification_denied
            },
        )
        notificationPermissionButton.isEnabled = !notificationGranted

        val overlayGranted = Settings.canDrawOverlays(this)
        overlayPermissionStatusView.setText(
            if (overlayGranted) R.string.overlay_granted else R.string.overlay_denied,
        )
        overlayPermissionButton.isEnabled = !overlayGranted

        // Keep this enabled so users can change or review the vendor policy even
        // after the current HyperOS value has been recognized.
        batteryPolicyStatusView.setText(
            when (batteryPolicyUiState()) {
                BatteryPolicyUiState.BACKGROUND_RESTRICTED ->
                    R.string.battery_policy_background_restricted
                BatteryPolicyUiState.HYPER_OS_UNRESTRICTED ->
                    R.string.battery_policy_hyperos_unrestricted
                BatteryPolicyUiState.HYPER_OS_NOT_UNRESTRICTED ->
                    R.string.battery_policy_hyperos_not_unrestricted
                BatteryPolicyUiState.AOSP_POWER_ALLOWLISTED ->
                    R.string.battery_policy_aosp_allowlisted
                BatteryPolicyUiState.VENDOR_POLICY_UNVERIFIED ->
                    R.string.battery_policy_vendor_unverified
            },
        )
        if (ScreenTranslationService.isRunning) {
            CaptureShortcutNotification.cancel(this)
        } else {
            CaptureShortcutNotification.show(this)
        }
    }

    private fun invalidatePreparedModel() {
        modelPreparationGeneration += 1
        modelPreparationEngine?.close()
        modelPreparationEngine = null
        modelReadyFor = null
        setServiceRunningUi(ScreenTranslationService.isRunning)
        modelStatusView.setText(
            if (selectedSourceLanguage() == selectedTargetLanguage()) {
                R.string.model_same_language
            } else {
                R.string.model_not_prepared
            },
        )
    }

    private fun persistCurrentConfiguration() {
        if (!::preferences.isInitialized) return
        preferences.save(
            sourceLanguage = selectedSourceLanguage().languageTag,
            targetLanguage = selectedTargetLanguage().languageTag,
            frameIntervalMs = selectedFrameInterval(),
            captureMode = selectedCaptureMode(),
        )
    }

    private fun selectedSourceLanguage(): LanguageOption {
        return availableSourceOptions[
            sourceSpinner.selectedItemPosition.coerceIn(
                0,
                availableSourceOptions.lastIndex,
            )
        ]
    }

    private fun selectedTargetLanguage(): LanguageOption {
        return availableTargetOptions[
            targetSpinner.selectedItemPosition.coerceIn(
                0,
                availableTargetOptions.lastIndex,
            )
        ]
    }

    private fun selectedFrameInterval(): Long {
        return FRAME_INTERVAL_OPTIONS_MS[
            intervalSeekBar.progress.coerceIn(0, FRAME_INTERVAL_OPTIONS_MS.lastIndex)
        ]
    }

    private fun selectedCaptureMode(): CaptureMode =
        CaptureMode.entries[
            captureModeSpinner.selectedItemPosition.coerceIn(0, CaptureMode.entries.lastIndex)
        ]

    private fun updateCaptureModeHint() {
        captureModeHintView.setText(
            if (selectedCaptureMode() == CaptureMode.FULL_SCREEN_INCREMENTAL) {
                R.string.capture_mode_full_screen_hint
            } else {
                R.string.capture_mode_region_hint
            },
        )
    }

    private fun updateIntervalLabel() {
        intervalValueView.text = getString(
            R.string.frame_interval_value,
            selectedFrameInterval(),
        )
    }

    private fun hasNotificationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setServiceRunningUi(running: Boolean) {
        val operationIdle =
            modelPreparationEngine == null && experimentalSmokeTestEngine == null
        val currentPair = selectedSourceLanguage().languageTag to selectedTargetLanguage().languageTag
        val prepareState = resolveModelPreparationButtonState(
            serviceRunning = running,
            operationIdle = operationIdle,
            sameLanguage = selectedSourceLanguage() == selectedTargetLanguage(),
            readyForSelectedPair = modelReadyFor == currentPair,
        )
        startButton.isEnabled = !running && operationIdle
        stopButton.isEnabled = running
        prepareModelsButton.setText(
            if (prepareState.isReady) R.string.model_prepare_ready else R.string.prepare_models,
        )
        prepareModelsButton.isEnabled = prepareState.isEnabled
        captureModeSpinner.isEnabled = !running && operationIdle
        manageModelsButton.isEnabled = !running && operationIdle
        onlineSettingsButton.isEnabled =
            !running && operationIdle && BuildConfig.ONLINE_LLM
        experimentalSmokeTestButton.isEnabled =
            !running && operationIdle && BuildConfig.HYMT2_Q4_EXPERIMENTAL
        if (running && serviceStatusView.text == getString(R.string.service_idle)) {
            serviceStatusView.setText(R.string.service_running)
        }
    }

    private fun refreshOnlineConfigurationStatus() {
        if (!BuildConfig.ONLINE_LLM || !::onlineConfigStatusView.isInitialized) return
        onlineConfigStatusView.text = runCatching {
            val bridge = Class.forName(ONLINE_EDITION_BRIDGE_CLASS)
            val method = bridge.getMethod("configurationSummary", Context::class.java)
            method.invoke(null, this) as String
        }.getOrElse {
            getString(R.string.online_config_status_unavailable)
        }
    }

    private fun refreshServiceStatus() {
        if (!::serviceStatusView.isInitialized) return
        val serviceRunning = ScreenTranslationService.isRunning
        setServiceRunningUi(serviceRunning)
        if (projectionRequestInFlight) return
        serviceStatusView.setText(
            when {
                serviceRunning -> R.string.service_running
                ScreenTranslationService.needsProjectionRestart ->
                    R.string.service_projection_stopped

                else -> R.string.service_idle
            },
        )
    }

    companion object {
        private val FRAME_INTERVAL_OPTIONS_MS = longArrayOf(
            250L,
            500L,
            750L,
            1_000L,
            1_500L,
            2_000L,
            3_000L,
        )
        private const val DEFAULT_INTERVAL_INDEX = 2
        private const val EXPERIMENTAL_SMOKE_LOG_TAG = "HyMt2Q4Smoke"
        private const val ONLINE_SETTINGS_ACTIVITY_CLASS =
            "com.screentranslation.app.online.OnlineSettingsActivity"
        private const val ONLINE_EDITION_BRIDGE_CLASS =
            "com.screentranslation.app.online.OnlineEditionBridge"
        private const val STATE_READY_SOURCE = "ready_source"
        private const val STATE_READY_TARGET = "ready_target"
        private const val EXPERIMENTAL_SMOKE_SOURCE_LANGUAGE = "en"
        private const val EXPERIMENTAL_SMOKE_TARGET_LANGUAGE = "zh"
        private const val EXPERIMENTAL_SMOKE_SOURCE_TEXT =
            "Although the committee acknowledged that the proposal could reduce costs " +
                "in the short term, it postponed the vote because no one could explain " +
                "how the system would protect users whose accounts had been flagged by mistake."
    }
}
