package com.screentranslation.app

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.ModelPreparationStage
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationBackendFactory
import com.screentranslation.app.model.LanguageOption
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.service.ScreenTranslationService

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var projectionManager: MediaProjectionManager

    private lateinit var experimentalBannerView: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var intervalSeekBar: SeekBar
    private lateinit var intervalValueView: TextView
    private lateinit var prepareModelsButton: Button
    private lateinit var modelStatusView: TextView
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

    private val availableSourceOptions: List<LanguageOption> = LanguageOption.sourceOptions
    private val availableTargetOptions: List<LanguageOption> by lazy {
        if (BuildConfig.HYMT2_Q4_EXPERIMENTAL) {
            listOf(LanguageOption.CHINESE_SIMPLIFIED)
        } else {
            LanguageOption.targetOptions
        }
    }

    private var modelPreparationEngine: TranslationBackend? = null
    private var modelPreparationGeneration = 0
    private var modelReadyFor: Pair<String, String>? = null
    private var experimentalSmokeTestEngine: TranslationBackend? = null
    private var experimentalSmokeTestGeneration = 0
    private var languageListenersReady = false
    private var pendingStartAfterNotificationPermission = false
    private var pendingStartAfterOverlayPermission = false
    private var projectionRequestInFlight = false
    private var projectionStoppedNotice = false

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
        preferences.save(source.languageTag, target.languageTag, interval)

        try {
            val serviceIntent = ScreenTranslationService.startIntent(
                context = this,
                resultCode = result.resultCode,
                resultData = projectionData,
                sourceLanguage = source.languageTag,
                targetLanguage = target.languageTag,
                frameIntervalMs = interval,
            )
            ContextCompat.startForegroundService(this, serviceIntent)
            serviceStatusView.setText(R.string.service_starting)
            setServiceRunningUi(true)
        } catch (error: Exception) {
            serviceStatusView.text = getString(
                R.string.start_failed,
                error.localizedMessage ?: error.javaClass.simpleName,
            )
            setServiceRunningUi(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = AppPreferences(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        bindViews()
        applySystemBarInsets()
        configureLanguageSelectors()
        configureFrameInterval()
        configureActions()
        refreshPermissionState()
        handleLaunchIntent(intent)
        setServiceRunningUi(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
        refreshServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::overlayPermissionStatusView.isInitialized) {
            refreshPermissionState()
            maybeContinueAfterOverlayPermission()
            refreshServiceStatus()
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

    private fun bindViews() {
        experimentalBannerView = findViewById(R.id.text_experimental_banner)
        experimentalBannerView.visibility = if (BuildConfig.HYMT2_Q4_EXPERIMENTAL) {
            View.VISIBLE
        } else {
            View.GONE
        }
        sourceSpinner = findViewById(R.id.spinner_source_language)
        targetSpinner = findViewById(R.id.spinner_target_language)
        intervalSeekBar = findViewById(R.id.seek_frame_interval)
        intervalValueView = findViewById(R.id.text_frame_interval)
        prepareModelsButton = findViewById(R.id.button_prepare_models)
        modelStatusView = findViewById(R.id.text_model_status)
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

    private fun configureActions() {
        prepareModelsButton.setOnClickListener {
            prepareCurrentModels()
        }
        experimentalSmokeTestButton.setOnClickListener {
            runExperimentalSmokeTest()
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
                prepareModelsButton.isEnabled = true
                startButton.isEnabled = true

                val currentPair = selectedSourceLanguage().languageTag to
                    selectedTargetLanguage().languageTag
                if (currentPair != requestedPair) {
                    modelStatusView.setText(R.string.model_not_prepared)
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
                        onReady?.invoke()
                    },
                    onFailure = { error ->
                        modelReadyFor = null
                        modelStatusView.text = getString(
                            R.string.model_failed,
                            error.localizedMessage ?: getString(R.string.unknown_error),
                        )
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

        val generation = ++experimentalSmokeTestGeneration
        experimentalSmokeTestEngine?.close()
        val engine = TranslationBackendFactory.create(
            context = this,
            sourceLanguage = EXPERIMENTAL_SMOKE_SOURCE_LANGUAGE,
            targetLanguage = EXPERIMENTAL_SMOKE_TARGET_LANGUAGE,
        )
        experimentalSmokeTestEngine = engine
        experimentalSmokeTestButton.isEnabled = false
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
            experimentalSmokeTestButton.isEnabled = true
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
        if (!hasNotificationPermission()) {
            pendingStartAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueStartAfterNotificationPermission()
    }

    private fun continueStartAfterNotificationPermission() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStartAfterOverlayPermission = true
            openOverlayPermissionSettings()
            return
        }
        requestProjectionPermission()
    }

    private fun requestProjectionPermission() {
        if (projectionRequestInFlight) return
        projectionStoppedNotice = false
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
        val candidates = listOf(
            // HyperOS 3 ignores the package URI on the standard overlay action
            // and opens a global app list. Its permission editor reliably opens
            // this app first; the user then chooses Other permissions > Floating windows.
            Intent(HYPER_OS_APP_PERMISSION_EDITOR).apply {
                setClassName(
                    HYPER_OS_SECURITY_CENTER_PACKAGE,
                    HYPER_OS_PERMISSION_EDITOR_ACTIVITY,
                )
                putExtra(HYPER_OS_PACKAGE_EXTRA, packageName)
            },
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
        val candidates = listOf(
            // Verified on HyperOS V816 / Android 16: the per-app power policy
            // lives inside the vendor app-info screen as its 省电策略 entry.
            // miui.intent.action.POWER_HIDE_MODE_APP_LIST does not resolve on
            // this release, and com.miui.securitycenter.action.POWER_SETTINGS
            // opens the *global* optimization page with no per-app control.
            Intent().apply {
                setClassName(
                    HYPER_OS_SECURITY_CENTER_PACKAGE,
                    HYPER_OS_APP_DETAILS_ACTIVITY,
                )
                putExtra(HYPER_OS_APP_DETAILS_PACKAGE_EXTRA, packageName)
            },
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

    /**
     * Reports only the AOSP battery optimization whitelist. A vendor power
     * policy is not readable from here, so the wording stays advisory rather
     * than claiming the service is safe from being killed.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true

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

        // The button stays enabled either way: the vendor power policy is a
        // separate setting that this check cannot see.
        batteryPolicyStatusView.setText(
            if (isIgnoringBatteryOptimizations()) {
                R.string.battery_policy_unrestricted
            } else {
                R.string.battery_policy_restricted
            },
        )
    }

    private fun invalidatePreparedModel() {
        modelPreparationGeneration += 1
        modelPreparationEngine?.close()
        modelPreparationEngine = null
        modelReadyFor = null
        prepareModelsButton.isEnabled = true
        startButton.isEnabled = true
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
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        if (running && serviceStatusView.text == getString(R.string.service_idle)) {
            serviceStatusView.setText(R.string.service_running)
        }
    }

    private fun handleLaunchIntent(launchIntent: Intent?) {
        if (launchIntent?.getBooleanExtra(EXTRA_PROJECTION_STOPPED, false) == true) {
            projectionStoppedNotice = true
            launchIntent.removeExtra(EXTRA_PROJECTION_STOPPED)
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
                projectionStoppedNotice ||
                    ScreenTranslationService.needsProjectionRestart ->
                    R.string.service_projection_stopped

                else -> R.string.service_idle
            },
        )
    }

    companion object {
        const val EXTRA_PROJECTION_STOPPED =
            "com.screentranslation.app.extra.PROJECTION_STOPPED"

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
        private const val HYPER_OS_APP_PERMISSION_EDITOR =
            "miui.intent.action.APP_PERM_EDITOR"
        private const val HYPER_OS_SECURITY_CENTER_PACKAGE =
            "com.miui.securitycenter"
        private const val HYPER_OS_PERMISSION_EDITOR_ACTIVITY =
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        private const val HYPER_OS_PACKAGE_EXTRA = "extra_pkgname"
        private const val HYPER_OS_APP_DETAILS_ACTIVITY =
            "com.miui.appmanager.ApplicationsDetailsActivity"
        private const val HYPER_OS_APP_DETAILS_PACKAGE_EXTRA = "package_name"
        private const val EXPERIMENTAL_SMOKE_LOG_TAG = "HyMt2Q4Smoke"
        private const val EXPERIMENTAL_SMOKE_SOURCE_LANGUAGE = "en"
        private const val EXPERIMENTAL_SMOKE_TARGET_LANGUAGE = "zh"
        private const val EXPERIMENTAL_SMOKE_SOURCE_TEXT =
            "Although the committee acknowledged that the proposal could reduce costs " +
                "in the short term, it postponed the vote because no one could explain " +
                "how the system would protect users whose accounts had been flagged by mistake."
    }
}
