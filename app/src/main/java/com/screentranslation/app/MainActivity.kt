package com.screentranslation.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModel
import androidx.work.WorkInfo
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.ModelPreparationStage
import com.screentranslation.app.ml.OcrProfileId
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationBackendFactory
import com.screentranslation.app.model.LanguageOption
import com.screentranslation.app.model.CaptureMode
import com.screentranslation.app.model.UiStyle
import com.screentranslation.app.model.preparation.ModelPreparationCoordinator
import com.screentranslation.app.model.preparation.ModelPreparationPhase
import com.screentranslation.app.model.preparation.ModelPreparationSnapshot
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.service.CapturePermissionPreconditions
import com.screentranslation.app.service.CapturePermissionStep
import com.screentranslation.app.service.CaptureShortcutNotification
import com.screentranslation.app.service.ScreenTranslationService
import com.screentranslation.app.ui.UiStyleController
import com.screentranslation.app.vendor.HyperOsVendorAdapter
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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

internal fun hasSelectedLanguagePairChanged(
    persistedSource: String,
    persistedTarget: String,
    selectedSource: String,
    selectedTarget: String,
): Boolean = persistedSource != selectedSource || persistedTarget != selectedTarget

internal data class RetainedModelReadiness(
    val pair: Pair<String, String>,
    val identity: String,
    val generation: Long,
)

internal class ModelReadinessViewModel : ViewModel() {
    var generation: Long = 0L
        private set
    var snapshot: RetainedModelReadiness? = null
        private set

    fun invalidate() {
        generation += 1L
        snapshot = null
    }

    fun beginVerification(): Long {
        invalidate()
        return generation
    }

    fun markReady(
        pair: Pair<String, String>,
        identity: String,
        expectedGeneration: Long? = null,
    ): Boolean {
        if (expectedGeneration != null && generation != expectedGeneration) return false
        if (expectedGeneration == null) generation += 1L
        snapshot = RetainedModelReadiness(pair, identity, generation)
        return true
    }
}

/**
 * Owns one cancellable resource for a generation of background work.
 *
 * Installation and cancellation share one monitor, so a resource created by a
 * worker either becomes visible to the lifecycle owner or is closed before it
 * can start expensive work. Exactly one side owns the final close.
 */
internal class GenerationOwnedResourceController<T : AutoCloseable> {
    private val lock = Any()
    private var generation = 0
    private var resource: T? = null
    private var running = false

    val inFlight: Boolean
        get() = synchronized(lock) { running }

    fun begin(): Int {
        val stale: T?
        val nextGeneration: Int
        synchronized(lock) {
            generation += 1
            nextGeneration = generation
            running = true
            stale = resource
            resource = null
        }
        close(stale)
        return nextGeneration
    }

    fun install(expectedGeneration: Int, candidate: T): Boolean {
        val installed = synchronized(lock) {
            when {
                generation != expectedGeneration || !running -> false
                resource == null -> {
                    resource = candidate
                    true
                }
                resource === candidate -> true
                else -> false
            }
        }
        if (!installed) close(candidate)
        return installed
    }

    fun release(candidate: T) {
        val owned = synchronized(lock) {
            if (resource === candidate) {
                resource = null
                candidate
            } else {
                null
            }
        }
        close(owned)
    }

    fun finish(expectedGeneration: Int): Boolean = synchronized(lock) {
        if (generation == expectedGeneration && running && resource == null) {
            running = false
            true
        } else {
            false
        }
    }

    fun cancel() {
        val owned: T?
        synchronized(lock) {
            generation += 1
            running = false
            owned = resource
            resource = null
        }
        close(owned)
    }

    private fun close(value: T?) {
        if (value != null) runCatching { value.close() }
    }
}

internal fun retainedReadinessMatches(
    snapshot: RetainedModelReadiness?,
    selectedPair: Pair<String, String>,
    currentIdentity: String?,
): Boolean = snapshot?.pair == selectedPair &&
    currentIdentity != null && snapshot.identity == currentIdentity

internal fun shouldStartModelReadinessCheck(
    activityStarted: Boolean,
    serviceRunning: Boolean,
    operationIdle: Boolean,
    readyForSelectedPair: Boolean,
): Boolean = activityStarted && !serviceRunning && operationIdle && !readyForSelectedPair

class MainActivity : AppCompatActivity() {
    private val modelReadinessViewModel: ModelReadinessViewModel by viewModels()
    private lateinit var preferences: AppPreferences
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var modelPreparationCoordinator: ModelPreparationCoordinator

    private lateinit var experimentalBannerView: TextView
    private lateinit var taskSummaryView: TextView
    private lateinit var readinessSummaryView: TextView
    private lateinit var uiStyleGroup: MaterialButtonToggleGroup
    private lateinit var materialMonetSwitch: MaterialSwitch
    private lateinit var materialMonetHintView: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var captureModeSpinner: Spinner
    private lateinit var captureModeHintView: TextView
    private lateinit var ocrProfileSpinner: Spinner
    private lateinit var ocrProfileHintView: TextView
    private lateinit var intervalSeekBar: SeekBar
    private lateinit var intervalValueView: TextView
    private lateinit var prepareModelsButton: Button
    private var prepareModelsButtonDefaultTint: ColorStateList? = null
    private lateinit var prepareModelsButtonDefaultTextColors: ColorStateList
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
    private lateinit var idleShortcutSwitch: MaterialSwitch
    private lateinit var aboutButton: Button
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

    private var modelReadyFor: Pair<String, String>? = null
    private var modelPreparationWorkInfos: List<WorkInfo> = emptyList()
    private var pendingStartAfterModelPreparation = false
    private val modelReadinessController =
        GenerationOwnedResourceController<TranslationBackend>()
    private val modelReadinessExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "model-readiness-verifier").apply { isDaemon = true }
    }
    private var experimentalSmokeTestEngine: TranslationBackend? = null
    private var experimentalSmokeTestGeneration = 0
    private var languageListenersReady = false
    private var pendingStartAfterNotificationPermission = false
    private var pendingStartAfterOverlayPermission = false
    private var projectionRequestInFlight = false
    private var sessionStateReceiverRegistered = false
    private var activityStarted = false

    private val sessionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenTranslationService.ACTION_SESSION_STATE_CHANGED) {
                refreshServiceStatus()
                reconcileModelReadiness()
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
        reconcileModelReadiness()
    }

    private val modelManagementLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                invalidatePreparedModel()
                prepareCurrentModels()
            }
            ModelManagementActivity.RESULT_MODELS_CHANGED -> {
                invalidatePreparedModel()
                reconcileModelReadiness()
            }
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
        preferences.setOcrProfile(selectedCaptureMode(), selectedOcrProfile())

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
                com.screentranslation.app.util.UserFacingErrorMapper.map(error).summary,
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
        modelPreparationCoordinator = ModelPreparationCoordinator(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        bindViews()
        taskSummaryView.isAccessibilityHeading = true
        ViewCompat.setAccessibilityPaneTitle(
            findViewById(android.R.id.content),
            getString(R.string.app_name),
        )
        applySystemBarInsets()
        configureAppearance()
        configureLanguageSelectors()
        configureCaptureMode()
        configureOcrProfile()
        configureFrameInterval()
        configureActions()
        modelReadyFor = null
        refreshPermissionState()
        setServiceRunningUi(false)
        refreshOnlineConfigurationStatus()
        modelPreparationCoordinator.observeAll().observe(this) { workInfos ->
            modelPreparationWorkInfos = workInfos
            renderModelPreparationTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshServiceStatus()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
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
        reconcileModelReadiness()
    }

    override fun onResume() {
        super.onResume()
        if (::overlayPermissionStatusView.isInitialized) {
            refreshPermissionState()
            maybeContinueAfterOverlayPermission()
            refreshServiceStatus()
            refreshOnlineConfigurationStatus()
        }
        if (::modelStatusView.isInitialized) reconcileModelReadiness()
    }

    override fun onDestroy() {
        cancelModelReadinessCheck()
        modelReadinessExecutor.shutdownNow()
        experimentalSmokeTestGeneration += 1
        experimentalSmokeTestEngine?.close()
        experimentalSmokeTestEngine = null
        super.onDestroy()
    }

    override fun onStop() {
        activityStarted = false
        cancelModelReadinessCheck()
        if (sessionStateReceiverRegistered) {
            unregisterReceiver(sessionStateReceiver)
            sessionStateReceiverRegistered = false
        }
        super.onStop()
    }

    private fun bindViews() {
        experimentalBannerView = findViewById(R.id.text_experimental_banner)
        taskSummaryView = findViewById(R.id.text_task_summary)
        readinessSummaryView = findViewById(R.id.text_readiness_summary)
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
        ocrProfileSpinner = findViewById(R.id.spinner_ocr_profile)
        ocrProfileHintView = findViewById(R.id.text_ocr_profile_hint)
        intervalSeekBar = findViewById(R.id.seek_frame_interval)
        intervalValueView = findViewById(R.id.text_frame_interval)
        prepareModelsButton = findViewById(R.id.button_prepare_models)
        prepareModelsButtonDefaultTint = prepareModelsButton.backgroundTintList
        prepareModelsButtonDefaultTextColors = prepareModelsButton.textColors
        manageModelsButton = findViewById(R.id.button_manage_models)
        modelStatusView = findViewById(R.id.text_model_status)
        // Readiness is restored from a retained, artifact-bound identity or a
        // fresh integrity check, never from the framework's view-state Bundle.
        prepareModelsButton.isSaveEnabled = false
        modelStatusView.isSaveEnabled = false
        onlineSettingsButton = findViewById(R.id.button_online_settings)
        onlineConfigStatusView = findViewById(R.id.text_online_config_status)
        experimentalSmokeTestButton = findViewById(R.id.button_experimental_smoke_test)
        experimentalSmokeTestResultView =
            findViewById(R.id.text_experimental_smoke_test_result)
        // A process recreation must not resurrect an in-progress self-test label.
        experimentalSmokeTestResultView.isSaveEnabled = false
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
        idleShortcutSwitch = findViewById(R.id.switch_idle_shortcut)
        aboutButton = findViewById(R.id.button_about)
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
        idleShortcutSwitch.isChecked = preferences.idleShortcutEnabled
        idleShortcutSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.idleShortcutEnabled = enabled
            if (enabled) CaptureShortcutNotification.show(this)
            else CaptureShortcutNotification.cancel(this)
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
                val selectedSource = selectedSourceLanguage().languageTag
                val selectedTarget = selectedTargetLanguage().languageTag
                if (
                    !hasSelectedLanguagePairChanged(
                        persistedSource = preferences.sourceLanguage,
                        persistedTarget = preferences.targetLanguage,
                        selectedSource = selectedSource,
                        selectedTarget = selectedTarget,
                    )
                ) {
                    return
                }
                persistCurrentConfiguration()
                invalidatePreparedModel()
                reconcileModelReadiness()
                renderHomeReadiness()
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
                if (::ocrProfileSpinner.isInitialized && ocrProfileSpinner.adapter != null) {
                    syncOcrProfileSelection()
                }
                persistCurrentConfiguration()
                renderHomeReadiness()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun configureOcrProfile() {
        ocrProfileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.ocr_profile_balanced),
                getString(R.string.ocr_profile_small_subtitle),
                getString(R.string.ocr_profile_document),
            ),
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        syncOcrProfileSelection()
        ocrProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                preferences.setOcrProfile(selectedCaptureMode(), selectedOcrProfile())
                updateOcrProfileHint()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun syncOcrProfileSelection() {
        val selected = preferences.ocrProfile(selectedCaptureMode())
        ocrProfileSpinner.setSelection(selected.ordinal, false)
        updateOcrProfileHint()
    }

    private fun updateOcrProfileHint() {
        ocrProfileHintView.setText(
            when (selectedOcrProfile()) {
                OcrProfileId.BALANCED -> R.string.ocr_profile_balanced_hint
                OcrProfileId.SMALL_SUBTITLE -> R.string.ocr_profile_small_subtitle_hint
                OcrProfileId.DOCUMENT -> R.string.ocr_profile_document_hint
            },
        )
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
        aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        startButton.setOnClickListener {
            handlePrimaryAction()
        }
        stopButton.setOnClickListener {
            stopTranslationService()
        }
    }

    private fun handlePrimaryAction() {
        persistCurrentConfiguration()
        when (currentHomeReadiness().action) {
            HomePrimaryAction.STOP_CAPTURE -> stopTranslationService()
            HomePrimaryAction.CONFIGURE_ONLINE -> {
                if (BuildConfig.ONLINE_LLM) {
                    onlineSettingsLauncher.launch(
                        Intent().setClassName(this, ONLINE_SETTINGS_ACTIVITY_CLASS),
                    )
                }
            }
            HomePrimaryAction.PREPARE_MODEL -> prepareCurrentModels {
                continueStartAfterModelPreparation()
            }
            HomePrimaryAction.REQUEST_NOTIFICATION -> {
                pendingStartAfterNotificationPermission = true
                requestNotificationPermission()
            }
            HomePrimaryAction.REQUEST_OVERLAY -> {
                pendingStartAfterOverlayPermission = true
                openOverlayPermissionSettings()
            }
            HomePrimaryAction.REQUEST_PROJECTION -> requestProjectionPermission()
            HomePrimaryAction.FIX_LANGUAGE_PAIR,
            HomePrimaryAction.WAIT_FOR_MODEL,
            -> Unit
        }
    }

    private fun prepareCurrentModels(onReady: (() -> Unit)? = null) {
        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        if (source == target) {
            modelStatusView.setText(R.string.model_same_language)
            return
        }

        cancelModelReadinessCheck()
        modelReadinessViewModel.invalidate()
        modelReadyFor = null
        pendingStartAfterModelPreparation = onReady != null
        runCatching {
            modelPreparationCoordinator.enqueue(
                sourceLanguage = source.languageTag,
                targetLanguage = target.languageTag,
                requireUnmeteredNetwork = false,
            )
        }.getOrElse { error ->
            pendingStartAfterModelPreparation = false
            modelStatusView.text = getString(
                R.string.model_failed,
                com.screentranslation.app.util.UserFacingErrorMapper.map(error).summary,
            )
            setServiceRunningUi(ScreenTranslationService.isRunning)
            return
        }
        modelStatusView.setText(R.string.model_progress_queued)
        setServiceRunningUi(ScreenTranslationService.isRunning)
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
            ModelPreparationStage.EXTRACTING -> getString(R.string.model_progress_extracting)
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
        val preparedIdentity = translation.getOrNull()?.let {
            runCatching { engine.currentPreparationIdentity() }.getOrNull()
        }
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
                        if (preparedIdentity != null) {
                            modelReadyFor = smokePair
                            modelReadinessViewModel.markReady(smokePair, preparedIdentity)
                        } else {
                            modelReadyFor = null
                            modelReadinessViewModel.invalidate()
                        }
                        if (preparedIdentity != null) {
                            modelStatusView.text = getString(
                                R.string.model_ready,
                                selectedSourceLanguage().displayName(this),
                                selectedTargetLanguage().displayName(this),
                            )
                        } else {
                            modelStatusView.setText(R.string.model_not_prepared)
                        }
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
                        com.screentranslation.app.util.UserFacingErrorMapper.map(error).summary,
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
                com.screentranslation.app.util.UserFacingErrorMapper.map(error).summary,
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
                com.screentranslation.app.util.UserFacingErrorMapper.map(error).summary,
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
        renderHomeReadiness()
    }

    private fun invalidatePreparedModel() {
        cancelModelReadinessCheck()
        modelReadinessViewModel.invalidate()
        pendingStartAfterModelPreparation = false
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

    private fun reconcileModelReadiness() {
        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        val requestedPair = source.languageTag to target.languageTag
        val serviceRunning = ScreenTranslationService.isRunning
        if (serviceRunning) {
            // The running service already owns the backend/runtime. Do not read
            // large model artifacts concurrently from a hidden Activity.
            cancelModelReadinessCheck()
            setServiceRunningUi(true)
            return
        }

        val taskSnapshot = currentModelTaskSnapshot()
        if (taskSnapshot?.isActive == true || taskSnapshot?.phase == ModelPreparationPhase.PAUSED) {
            modelReadyFor = null
            modelStatusView.text = modelPreparationTaskStatus(taskSnapshot)
            setServiceRunningUi(serviceRunning)
            return
        }

        val retained = modelReadinessViewModel.snapshot
        val currentIdentity = if (retained?.pair == requestedPair) {
            currentPreparationIdentity(requestedPair)
        } else {
            null
        }
        if (retainedReadinessMatches(retained, requestedPair, currentIdentity)) {
            modelReadyFor = requestedPair
            showSelectedPairReady()
            setServiceRunningUi(serviceRunning)
            return
        }
        if (modelReadinessController.inFlight) {
            // onResume follows onStart and must not replace the long-running
            // Full hash status with a misleading "not prepared" label.
            modelReadyFor = null
            modelStatusView.setText(R.string.model_progress_verifying)
            setServiceRunningUi(serviceRunning)
            return
        }
        if (retained != null) modelReadinessViewModel.invalidate()
        modelReadyFor = null
        modelStatusView.setText(
            if (source == target) R.string.model_same_language else R.string.model_not_prepared,
        )
        setServiceRunningUi(serviceRunning)

        val operationIdle = currentModelTaskSnapshot()?.isActive != true &&
            experimentalSmokeTestEngine == null && !modelReadinessController.inFlight
        if (
            source != target && shouldStartModelReadinessCheck(
                activityStarted = activityStarted,
                serviceRunning = serviceRunning,
                operationIdle = operationIdle,
                readyForSelectedPair = false,
            )
        ) {
            recoverPreparedModelFromStorage(requestedPair)
        }
    }

    private fun currentPreparationIdentity(pair: Pair<String, String>): String? {
        val engine = runCatching {
            TranslationBackendFactory.create(
                context = applicationContext,
                sourceLanguage = pair.first,
                targetLanguage = pair.second,
            )
        }.getOrNull() ?: return null
        return try {
            runCatching { engine.currentPreparationIdentity() }.getOrNull()
        } finally {
            runCatching { engine.close() }
        }
    }

    private fun recoverPreparedModelFromStorage(requestedPair: Pair<String, String>) {
        if (
            !shouldStartModelReadinessCheck(
                activityStarted = activityStarted,
                serviceRunning = ScreenTranslationService.isRunning,
                operationIdle = currentModelTaskSnapshot()?.isActive != true &&
                    experimentalSmokeTestEngine == null && !modelReadinessController.inFlight,
                readyForSelectedPair = modelReadyFor == requestedPair,
            )
        ) {
            return
        }

        val generation = modelReadinessController.begin()
        val processGeneration = modelReadinessViewModel.beginVerification()
        modelStatusView.setText(R.string.model_progress_verifying)
        setServiceRunningUi(ScreenTranslationService.isRunning)
        try {
            modelReadinessExecutor.execute {
                val engine = runCatching {
                    TranslationBackendFactory.create(
                        context = applicationContext,
                        sourceLanguage = requestedPair.first,
                        targetLanguage = requestedPair.second,
                    )
                }.getOrNull()

                if (engine == null) {
                    deliverRecoveredModelReadiness(
                        generation,
                        processGeneration,
                        requestedPair,
                        null,
                    )
                    return@execute
                }
                if (!modelReadinessController.install(generation, engine)) {
                    return@execute
                }

                val identity = try {
                    if (runCatching { engine.isPrepared() }.getOrDefault(false)) {
                        runCatching { engine.currentPreparationIdentity() }.getOrNull()
                    } else {
                        null
                    }
                } finally {
                    modelReadinessController.release(engine)
                }
                deliverRecoveredModelReadiness(
                    generation,
                    processGeneration,
                    requestedPair,
                    identity,
                )
            }
        } catch (_: RejectedExecutionException) {
            modelReadinessController.finish(generation)
            // Activity destruction owns executor shutdown; no UI result remains relevant.
        }
    }

    private fun deliverRecoveredModelReadiness(
        generation: Int,
        processGeneration: Long,
        requestedPair: Pair<String, String>,
        identity: String?,
    ) {
        runOnUiThread {
            if (!modelReadinessController.finish(generation) || isDestroyed) return@runOnUiThread
            if (processGeneration != modelReadinessViewModel.generation) return@runOnUiThread
            if (!activityStarted || ScreenTranslationService.isRunning) return@runOnUiThread
            val currentPair = selectedSourceLanguage().languageTag to
                selectedTargetLanguage().languageTag
            if (currentPair != requestedPair) return@runOnUiThread

            val ready = identity != null && modelReadinessViewModel.markReady(
                pair = requestedPair,
                identity = identity,
                expectedGeneration = processGeneration,
            )
            modelReadyFor = requestedPair.takeIf { ready }
            if (ready) {
                showSelectedPairReady()
            } else {
                modelStatusView.setText(R.string.model_not_prepared)
            }
            setServiceRunningUi(ScreenTranslationService.isRunning)
        }
    }

    private fun cancelModelReadinessCheck() {
        modelReadinessController.cancel()
    }

    private fun showSelectedPairReady() {
        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        modelStatusView.text = getString(
            R.string.model_ready,
            source.displayName(this),
            target.displayName(this),
        )
        ViewCompat.setStateDescription(modelStatusView, modelStatusView.text)
    }

    private fun selectedModelTaskId(): String? {
        if (!::modelPreparationCoordinator.isInitialized || !::sourceSpinner.isInitialized) return null
        val source = selectedSourceLanguage().languageTag
        val target = selectedTargetLanguage().languageTag
        if (source == target) return null
        return runCatching { modelPreparationCoordinator.descriptor(source, target).taskId }
            .getOrNull()
    }

    private fun currentModelTaskSnapshot(): ModelPreparationSnapshot? {
        val taskId = selectedModelTaskId() ?: return null
        return modelPreparationCoordinator.snapshotFor(modelPreparationWorkInfos, taskId)
    }

    private fun renderModelPreparationTask() {
        if (!::modelStatusView.isInitialized || !::sourceSpinner.isInitialized) return
        val snapshot = currentModelTaskSnapshot() ?: return
        when (snapshot.phase) {
            ModelPreparationPhase.READY -> {
                val source = selectedSourceLanguage().languageTag
                val target = selectedTargetLanguage().languageTag
                val identity = modelPreparationCoordinator.verifiedCurrentIdentity(source, target)
                if (identity != null) {
                    val pair = source to target
                    modelReadyFor = pair
                    modelReadinessViewModel.markReady(pair, identity)
                    showSelectedPairReady()
                    if (pendingStartAfterModelPreparation) {
                        pendingStartAfterModelPreparation = false
                        continueStartAfterModelPreparation()
                    }
                } else {
                    modelReadyFor = null
                    modelReadinessViewModel.invalidate()
                    modelStatusView.setText(R.string.model_not_prepared)
                }
            }
            ModelPreparationPhase.FAILED -> {
                pendingStartAfterModelPreparation = false
                modelReadyFor = null
                modelStatusView.text = getString(
                    R.string.model_failed,
                    snapshot.message ?: getString(R.string.unknown_error),
                )
            }
            ModelPreparationPhase.CANCELLED -> {
                pendingStartAfterModelPreparation = false
                modelReadyFor = null
                modelStatusView.setText(R.string.model_progress_cancelled)
            }
            else -> {
                modelReadyFor = null
                modelStatusView.text = modelPreparationTaskStatus(snapshot)
            }
        }
        ViewCompat.setStateDescription(modelStatusView, modelStatusView.text)
        setServiceRunningUi(ScreenTranslationService.isRunning)
    }

    private fun modelPreparationTaskStatus(snapshot: ModelPreparationSnapshot): String = when (
        snapshot.phase
    ) {
        ModelPreparationPhase.IDLE -> getString(R.string.model_not_prepared)
        ModelPreparationPhase.QUEUED -> getString(R.string.model_progress_queued)
        ModelPreparationPhase.WAITING_FOR_NETWORK -> getString(R.string.model_progress_waiting_network)
        ModelPreparationPhase.STORAGE_PREFLIGHT -> getString(R.string.model_progress_storage_preflight)
        ModelPreparationPhase.PREPARING -> getString(R.string.model_progress_preparing)
        ModelPreparationPhase.EXTRACTING -> getString(R.string.model_progress_extracting)
        ModelPreparationPhase.VERIFYING -> getString(R.string.model_progress_verifying)
        ModelPreparationPhase.PAUSED -> getString(R.string.model_progress_paused)
        ModelPreparationPhase.CANCELLED -> getString(R.string.model_progress_cancelled)
        ModelPreparationPhase.READY -> getString(R.string.model_prepare_ready)
        ModelPreparationPhase.FAILED -> getString(
            R.string.model_failed,
            snapshot.message ?: getString(R.string.unknown_error),
        )
        ModelPreparationPhase.DOWNLOADING -> {
            val percent = if (snapshot.totalBytes > 0L) {
                snapshot.completedBytes * 100L / snapshot.totalBytes
            } else {
                0L
            }
            getString(
                R.string.model_progress_downloading_detailed,
                Formatter.formatFileSize(this, snapshot.completedBytes),
                Formatter.formatFileSize(this, snapshot.totalBytes),
                percent.coerceIn(0L, 100L),
                Formatter.formatFileSize(this, snapshot.bytesPerSecond),
                snapshot.etaSeconds ?: 0L,
            )
        }
    }

    private fun persistCurrentConfiguration() {
        if (!::preferences.isInitialized) return
        preferences.save(
            sourceLanguage = selectedSourceLanguage().languageTag,
            targetLanguage = selectedTargetLanguage().languageTag,
            frameIntervalMs = selectedFrameInterval(),
            captureMode = selectedCaptureMode(),
        )
        preferences.setOcrProfile(selectedCaptureMode(), selectedOcrProfile())
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

    private fun selectedOcrProfile(): OcrProfileId = OcrProfileId.entries[
        ocrProfileSpinner.selectedItemPosition.coerceIn(0, OcrProfileId.entries.lastIndex)
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
            currentModelTaskSnapshot()?.isActive != true && experimentalSmokeTestEngine == null &&
                !modelReadinessController.inFlight
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
        if (prepareState.isReady) {
            prepareModelsButton.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    prepareModelsButton,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                ),
            )
            prepareModelsButton.setTextColor(
                MaterialColors.getColor(
                    prepareModelsButton,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
        } else {
            prepareModelsButton.backgroundTintList = prepareModelsButtonDefaultTint
            prepareModelsButton.setTextColor(prepareModelsButtonDefaultTextColors)
        }
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
        renderHomeReadiness()
    }

    private fun currentHomeReadiness(): HomeReadinessState {
        val pair = selectedSourceLanguage().languageTag to selectedTargetLanguage().languageTag
        return resolveHomeReadiness(
            serviceRunning = ScreenTranslationService.isRunning,
            sameLanguage = pair.first == pair.second,
            onlineConfigurationReady = !BuildConfig.ONLINE_LLM || isOnlineConfigurationReady(),
            modelTaskActive = currentModelTaskSnapshot()?.isActive == true,
            modelReady = modelReadyFor == pair,
            notificationGranted = hasNotificationPermission(),
            overlayGranted = Settings.canDrawOverlays(this),
        )
    }

    private fun renderHomeReadiness() {
        if (!::taskSummaryView.isInitialized || !::sourceSpinner.isInitialized) return
        val state = currentHomeReadiness()
        val captureMode = getString(
            if (selectedCaptureMode() == CaptureMode.FULL_SCREEN_INCREMENTAL) {
                R.string.capture_mode_full_screen
            } else {
                R.string.capture_mode_region
            },
        )
        taskSummaryView.text = getString(
            R.string.home_task_summary,
            selectedSourceLanguage().displayName(this),
            selectedTargetLanguage().displayName(this),
            captureMode,
        )
        val actionAndReason = when (state.action) {
            HomePrimaryAction.FIX_LANGUAGE_PAIR ->
                R.string.home_action_fix_languages to R.string.home_reason_fix_languages
            HomePrimaryAction.CONFIGURE_ONLINE ->
                R.string.home_action_configure_online to R.string.home_reason_configure_online
            HomePrimaryAction.PREPARE_MODEL ->
                R.string.home_action_prepare_model to R.string.home_reason_prepare_model
            HomePrimaryAction.WAIT_FOR_MODEL ->
                R.string.home_action_wait_model to R.string.home_reason_wait_model
            HomePrimaryAction.REQUEST_NOTIFICATION ->
                R.string.home_action_notification to R.string.home_reason_notification
            HomePrimaryAction.REQUEST_OVERLAY ->
                R.string.home_action_overlay to R.string.home_reason_overlay
            HomePrimaryAction.REQUEST_PROJECTION ->
                R.string.home_action_start to R.string.home_reason_start
            HomePrimaryAction.STOP_CAPTURE ->
                R.string.stop_translation to R.string.home_reason_running
        }
        startButton.setText(actionAndReason.first)
        startButton.isEnabled = !state.blocked
        readinessSummaryView.setText(actionAndReason.second)
        ViewCompat.setStateDescription(readinessSummaryView, readinessSummaryView.text)
        ViewCompat.setStateDescription(startButton, readinessSummaryView.text)
    }

    private fun isOnlineConfigurationReady(): Boolean {
        if (!BuildConfig.ONLINE_LLM) return true
        return runCatching {
            val bridge = Class.forName(ONLINE_EDITION_BRIDGE_CLASS)
            val method = bridge.getMethod("isConfigurationReady", Context::class.java)
            method.invoke(null, this) as Boolean
        }.getOrDefault(false)
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
        ViewCompat.setStateDescription(onlineConfigStatusView, onlineConfigStatusView.text)
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
        ViewCompat.setStateDescription(serviceStatusView, serviceStatusView.text)
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
        private const val EXPERIMENTAL_SMOKE_SOURCE_LANGUAGE = "en"
        private const val EXPERIMENTAL_SMOKE_TARGET_LANGUAGE = "zh"
        private const val EXPERIMENTAL_SMOKE_SOURCE_TEXT =
            "Although the committee acknowledged that the proposal could reduce costs " +
                "in the short term, it postponed the vote because no one could explain " +
                "how the system would protect users whose accounts had been flagged by mistake."
    }
}
