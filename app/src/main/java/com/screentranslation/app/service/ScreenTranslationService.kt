package com.screentranslation.app.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.service.quicksettings.TileService
import android.os.Looper
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import android.view.WindowManager
import com.screentranslation.app.MainActivity
import com.screentranslation.app.R
import com.screentranslation.app.model.CaptureMode
import com.screentranslation.app.capture.CapturePerformanceTelemetry
import com.screentranslation.app.capture.FrameProcessor
import com.screentranslation.app.capture.FramePipeline
import com.screentranslation.app.capture.FullScreenFrameProcessor
import com.screentranslation.app.ml.OcrEngine
import com.screentranslation.app.ml.OcrProfiles
import com.screentranslation.app.ml.PpOcrv6Engine
import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.ModelPreparationStage
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationBackendFactory
import com.screentranslation.app.overlay.OverlayController
import com.screentranslation.app.overlay.FullScreenOverlayController
import com.screentranslation.app.prefs.NormalizedRegionBounds
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.prefs.RegionPresetOrientation
import com.screentranslation.app.prefs.RegionPresetStore
import com.screentranslation.app.util.StableTextGate
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicReference

/**
 * One foreground-service lifetime represents one MediaProjection permission
 * session and creates exactly one VirtualDisplay. Rotation changes reuse that
 * display via resize + setSurface, as required by modern MediaProjection.
 */
class ScreenTranslationService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycle = CaptureLifecycleStateMachine()

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var overlayController: OverlayController? = null
    private var fullScreenOverlayController: FullScreenOverlayController? = null
    private var ocrEngine: OcrEngine? = null
    private var translationEngine: TranslationBackend? = null
    private var frameProcessor: FramePipeline? = null
    private var performanceTelemetry: CapturePerformanceTelemetry? = null
    private var captureMode = CaptureMode.REGION
    private var regionFrozen = false
    private var fullScreenUserPaused = false
    private var activeRegionPresetName: String? = null
    private var applyingRegionPreset = false
    private val performanceLogRunnable = object : Runnable {
        override fun run() {
            val telemetry = performanceTelemetry ?: return
            Log.i(PERFORMANCE_TAG, telemetry.snapshot("periodic").toJsonLine())
            if (sessionStarted && !closing) {
                mainHandler.postDelayed(this, PERFORMANCE_LOG_INTERVAL_MS)
            }
        }
    }

    @Volatile
    private var captureWidth = 0

    @Volatile
    private var captureHeight = 0

    @Volatile
    private var captureDensityDpi = 0
    private var sessionStarted = false
    private var closing = false
    private var projectionStoppedUnexpectedly = false
    private var startupFailureMessage: String? = null
    private var startupStage = STARTUP_STAGE_SERVICE_ENTRY

    /**
     * Vendor MediaProjection implementations do not reliably report a blank
     * screen through [MediaProjection.Callback.onCapturedContentVisibilityChanged],
     * so screen state is tracked independently as a second pause signal.
     */
    private var screenReceiver: BroadcastReceiver? = null

    @Volatile
    private var normalizedRegion = NormalizedRegion.FULL

    @Volatile
    private var overlayExclusionBounds = emptyList<Rect>()

    override fun onCreate() {
        super.onCreate()
        CaptureShortcutNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lifecycle.dispatch(
                CaptureLifecycleEvent.StopRequested(CaptureStopReason.USER),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_APPLY_REGION_PRESET) {
            if (sessionStarted && !closing && captureMode == CaptureMode.REGION) {
                applyRegionPresetRequest(intent)
            }
            return START_NOT_STICKY
        }

        // One service lifetime == one projection session, so a second ACTION_START
        // must be rejected. Each rejection reason is logged separately: they look
        // identical to the user but mean very different things on a bug report.
        if (intent?.action != ACTION_START || sessionStarted || closing) {
            when {
                closing ->
                    Log.w(TAG, "Ignoring ${intent?.action}: session is shutting down")

                sessionStarted ->
                    Log.w(TAG, "Ignoring ACTION_START: a session is already running")

                else ->
                    Log.w(TAG, "Ignoring unsupported service action: ${intent?.action}")
            }
            if (!sessionStarted) stopSelf(startId)
            return START_NOT_STICKY
        }

        // Keep the parcelable path for process recreation and a one-shot
        // in-process copy for the immediate Activity -> service handoff. The
        // latter prevents vendor parcel handling from turning a valid consent
        // callback into an unobservable startup failure.
        val inProcessRequest = pendingStartRequest.getAndSet(null)
        val projectionData = inProcessRequest?.projectionData ?: intent.getParcelableExtra(
            EXTRA_PROJECTION_DATA,
            Intent::class.java,
        )
        val resultCode = inProcessRequest?.resultCode ?: intent.getIntExtra(
            EXTRA_PROJECTION_RESULT_CODE,
            Activity.RESULT_CANCELED,
        )
        val sourceLanguage = inProcessRequest?.sourceLanguage
            ?: intent.getStringExtra(EXTRA_SOURCE_LANGUAGE)
        val targetLanguage = inProcessRequest?.targetLanguage
            ?: intent.getStringExtra(EXTRA_TARGET_LANGUAGE)
        val frameIntervalMs = (inProcessRequest?.frameIntervalMs ?: intent
            .getLongExtra(EXTRA_FRAME_INTERVAL_MS, FrameProcessor.DEFAULT_FRAME_INTERVAL_MS))
            .coerceAtLeast(0L)
        val requestedCaptureMode = inProcessRequest?.captureMode
            ?: CaptureMode.fromPersisted(intent.getStringExtra(EXTRA_CAPTURE_MODE))

        // Parse the mode before building the foreground notification so the
        // first visible notification describes the actual session rather than
        // briefly falling back to region mode.
        captureMode = requestedCaptureMode
        CaptureShortcutNotification.cancel(this)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        if (resultCode != Activity.RESULT_OK ||
            projectionData == null ||
            sourceLanguage.isNullOrBlank() ||
            targetLanguage.isNullOrBlank()
        ) {
            startupFailureMessage = getString(R.string.capture_start_invalid_payload)
            lifecycle.dispatch(
                CaptureLifecycleEvent.StopRequested(CaptureStopReason.STARTUP_FAILURE),
            )
            Log.e(
                TAG,
                "Refusing to start: resultCode=$resultCode hasData=${projectionData != null} " +
                    "sourcePresent=${!sourceLanguage.isNullOrBlank()} " +
                    "targetPresent=${!targetLanguage.isNullOrBlank()} " +
                    "inProcessHandoff=${inProcessRequest != null}",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startSession(
                resultCode = resultCode,
                projectionData = projectionData,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                frameIntervalMs = frameIntervalMs,
                captureMode = requestedCaptureMode,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start the capture session", error)
            lifecycle.dispatch(
                CaptureLifecycleEvent.StopRequested(CaptureStopReason.STARTUP_FAILURE),
            )
            startupFailureMessage = getString(
                R.string.capture_start_initialization_failed,
                startupStage,
                startupFailureDetail(error),
            )
            releaseSession()
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!sessionStarted || closing) return
        captureHandler?.post {
            resizeCaptureIfNeeded()
            mainHandler.post { applyActivePresetForConfiguration(newConfig) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        lifecycle.dispatch(CaptureLifecycleEvent.TaskRemoved)
        Log.i(TAG, "Host task removed; capture foreground service remains active")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val projectionStopped = projectionStoppedUnexpectedly
        releaseSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        CaptureShortcutNotification.show(
            context = this,
            projectionStopped = projectionStopped,
            startupFailureMessage = startupFailureMessage,
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        super.dump(fd, writer, args)
        val snapshot = performanceTelemetry?.snapshot("dumpsys")?.toJsonLine()
        writer.println("$PERFORMANCE_DUMP_PREFIX ${snapshot ?: "null"}")
    }

    private fun startSession(
        resultCode: Int,
        projectionData: Intent,
        sourceLanguage: String,
        targetLanguage: String,
        frameIntervalMs: Long,
        captureMode: CaptureMode,
    ) {
        this.captureMode = captureMode
        val ocrProfile = OcrProfiles.forId(AppPreferences(this).ocrProfile(captureMode))
        performanceTelemetry = CapturePerformanceTelemetry(
            captureMode = captureMode.persistedValue,
            baseIntervalMs = frameIntervalMs,
            ocrProfile = ocrProfile.id.persistedValue,
        )
        lifecycle.dispatch(
            CaptureLifecycleEvent.StartRequested(
                captureMode = captureMode,
                screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false,
            ),
        )
        startupStage = STARTUP_STAGE_OVERLAY
        if (captureMode == CaptureMode.FULL_SCREEN_INCREMENTAL) {
            val overlay = FullScreenOverlayController(
                context = this,
                onStop = ::requestUserStop,
                onOverlayBoundsChanged = ::onFullScreenOverlayBoundsChanged,
                onPausedChanged = ::onFullScreenPausedChanged,
            )
            check(overlay.show()) { "Overlay permission is required" }
            overlay.updateStatus(STATUS_PREPARING_MODEL)
            fullScreenOverlayController = overlay
        } else {
            val overlay = OverlayController(
                context = this,
                onRegionChanged = ::onPixelRegionChanged,
                onOverlayBoundsChanged = ::onRegionOverlayBoundsChanged,
                onStop = ::requestUserStop,
                onRegionCleared = ::onRegionCleared,
                onExpandedChanged = ::onResultsExpandedChanged,
                onFrozenChanged = ::onRegionFrozenChanged,
            )
            check(overlay.show()) { "Overlay permission is required" }
            overlay.updateStatus(STATUS_SELECT_REGION)
            overlayController = overlay
        }
        lifecycle.dispatch(CaptureLifecycleEvent.OverlayReady)

        startupStage = STARTUP_STAGE_SCREEN_RECEIVER
        registerScreenStateReceiver()

        startupStage = STARTUP_STAGE_CAPTURE_THREAD
        val handlerThread = HandlerThread(CAPTURE_THREAD_NAME).apply { start() }
        val handler = Handler(handlerThread.looper)
        captureThread = handlerThread
        captureHandler = handler

        startupStage = STARTUP_STAGE_BACKENDS
        val ocr = PpOcrv6Engine(this)
        val translator = TranslationBackendFactory.create(
            context = this,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
        )
        startupStage = STARTUP_STAGE_FRAME_PIPELINE
        val processor: FramePipeline = if (captureMode == CaptureMode.FULL_SCREEN_INCREMENTAL) {
            FullScreenFrameProcessor(
                ocrEngine = ocr,
                translationEngine = translator,
                sourceLanguageTag = sourceLanguage,
                targetLanguageTag = targetLanguage,
                ocrProfile = ocrProfile,
                frameIntervalMs = frameIntervalMs,
                onBlocks = { blocks ->
                    mainHandler.post {
                        if (!closing) {
                            val controller = fullScreenOverlayController
                            if (controller != null && !controller.hasOverlayPermission()) {
                                stopSelf()
                            } else {
                                controller?.updateBlocks(blocks)
                            }
                        }
                    }
                },
                onError = ::reportProcessingError,
                performanceTelemetry = performanceTelemetry,
            )
        } else {
            FrameProcessor(
                ocrEngine = ocr,
                translationEngine = translator,
                sourceLanguageTag = sourceLanguage,
                targetLanguageTag = targetLanguage,
                ocrProfile = ocrProfile,
                stableTextGate = StableTextGate(
                    requiredConsecutiveMatches = ocrProfile.stabilityObservations,
                ),
                frameIntervalMs = frameIntervalMs,
                onOriginalRecognized = { text ->
                    mainHandler.post {
                        if (!closing) {
                            val controller = overlayController
                            if (controller != null && !controller.hasOverlayPermission()) {
                                stopSelf()
                            } else {
                                // Keep the last successful translation visible
                                // until this latest-wins request completes. A
                                // timeout or provider error updates status only.
                                controller?.updateOriginal(text)
                                controller?.updateStatus(STATUS_TRANSLATING)
                            }
                        }
                    }
                },
                onTranslation = { result ->
                    mainHandler.post {
                        if (!closing) {
                            val controller = overlayController
                            if (controller != null && !controller.hasOverlayPermission()) {
                                stopSelf()
                            } else {
                                controller?.updateContent(
                                    result.originalText,
                                    result.translatedText,
                                )
                                controller?.updateStatus(STATUS_RUNNING)
                            }
                        }
                    }
                },
                onError = ::reportProcessingError,
                performanceTelemetry = performanceTelemetry,
            )
        }.apply {
            setEnabled(false)
        }
        ocrEngine = ocr
        translationEngine = translator
        frameProcessor = processor

        startupStage = STARTUP_STAGE_MEDIA_PROJECTION
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = checkNotNull(
            projectionManager.getMediaProjection(resultCode, projectionData),
        ) {
            "The system did not return a MediaProjection for the approved session"
        }
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (!closing) {
                        lifecycle.dispatch(CaptureLifecycleEvent.ProjectionRevoked)
                        Log.i(
                            TAG,
                            "MediaProjection stopped by the system; fresh user consent is required",
                        )
                        needsProjectionRestart = true
                        projectionStoppedUnexpectedly = true
                        stopSelf()
                    }
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                captureHandler?.post {
                    resizeCaptureIfNeeded(width, height)
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                mainHandler.post {
                    lifecycle.dispatch(
                        CaptureLifecycleEvent.ContentVisibilityChanged(isVisible),
                    )
                    refreshProcessorState()
                }
            }
        }
        projection.registerCallback(callback, handler)
        mediaProjection = projection
        projectionCallback = callback

        startupStage = STARTUP_STAGE_IMAGE_READER
        val spec = currentCaptureSpec()
        val reader = newImageReader(spec)
        imageReader = reader
        captureWidth = spec.width
        captureHeight = spec.height
        captureDensityDpi = spec.densityDpi

        // This is the only createVirtualDisplay call for this projection session.
        startupStage = STARTUP_STAGE_VIRTUAL_DISPLAY
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            spec.width,
            spec.height,
            spec.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
        lifecycle.dispatch(CaptureLifecycleEvent.ProjectionReady)
        sessionStarted = true
        isRunning = true
        currentLifecyclePhase = lifecycle.snapshot.phase
        notifySessionStateChanged()
        Log.i(
            PERFORMANCE_TAG,
            checkNotNull(performanceTelemetry).snapshot("started").toJsonLine(),
        )
        mainHandler.removeCallbacks(performanceLogRunnable)
        mainHandler.postDelayed(performanceLogRunnable, PERFORMANCE_LOG_INTERVAL_MS)
        needsProjectionRestart = false
        startupStage = STARTUP_STAGE_MODEL_PREPARATION
        updateOverlayStatus(STATUS_PREPARING_MODEL)
        translator.prepare(
            requireWifi = false,
            warmRuntime = true,
            onProgress = { progress ->
                mainHandler.post {
                    if (!closing) {
                        updateOverlayStatus(modelPreparationStatus(progress))
                    }
                }
            },
        ) { result ->
            mainHandler.post {
                if (closing) return@post
                result.fold(
                    onSuccess = {
                        lifecycle.dispatch(CaptureLifecycleEvent.ModelReady)
                        refreshProcessorState()
                    },
                    onFailure = { error ->
                        lifecycle.dispatch(CaptureLifecycleEvent.ModelUnavailable)
                        processor.setEnabled(false)
                        updateOverlayStatus(
                            "模型准备失败：${error.message ?: error.javaClass.simpleName}",
                        )
                    },
                )
            }
        }
    }

    private fun reportProcessingError(error: Throwable) {
        performanceTelemetry?.recordProcessingError()
        mainHandler.post {
            if (!closing) {
                overlayController?.preserveContentAfterFailure()
                updateOverlayStatus(
                    "处理失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun requestUserStop() {
        lifecycle.dispatch(
            CaptureLifecycleEvent.StopRequested(CaptureStopReason.USER),
        )
        stopSelf()
    }

    private fun updateOverlayStatus(status: String) {
        overlayController?.updateStatus(status)
        fullScreenOverlayController?.updateStatus(status)
    }

    private fun notifySessionStateChanged() {
        currentLifecyclePhase = lifecycle.snapshot.phase
        TileService.requestListeningState(
            this,
            ComponentName(this, CaptureQuickSettingsTileService::class.java),
        )
        sendBroadcast(
            Intent(ACTION_SESSION_STATE_CHANGED)
                .setPackage(packageName),
        )
    }

    /**
     * ACTION_SCREEN_OFF / ACTION_SCREEN_ON can only be received by a registered
     * receiver, never from the manifest. This is deliberately independent of
     * [MediaProjection.Callback.onCapturedContentVisibilityChanged]: that callback
     * is vendor-dependent and does not reliably fire on a blank screen, which
     * would leave the OCR pipeline burning battery on frames nobody can see.
     */
    private fun registerScreenStateReceiver() {
        lifecycle.dispatch(
            CaptureLifecycleEvent.ScreenStateChanged(
                getSystemService(PowerManager::class.java)?.isInteractive != false,
            ),
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val nowOn = intent?.action != Intent.ACTION_SCREEN_OFF
                if (nowOn == lifecycle.snapshot.screenOn) return
                lifecycle.dispatch(CaptureLifecycleEvent.ScreenStateChanged(nowOn))
                Log.i(TAG, "Screen ${if (nowOn) "on" else "off"}; processing enabled=$nowOn")
                refreshProcessorState()
            }
        }
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        screenReceiver = receiver
    }

    private fun modelPreparationStatus(progress: ModelPreparationProgress): String =
        when (progress.stage) {
            ModelPreparationStage.PREPARING -> getString(R.string.model_progress_preparing)
            ModelPreparationStage.VERIFYING -> getString(R.string.model_progress_verifying)
            ModelPreparationStage.EXTRACTING -> getString(R.string.model_progress_extracting)
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

    private fun newImageReader(spec: CaptureSpec): ImageReader =
        ImageReader.newInstance(
            spec.width,
            spec.height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        ).also { reader ->
            reader.setOnImageAvailableListener(
                { availableReader ->
                    frameProcessor?.onImageAvailable(
                        availableReader,
                        normalizedRegion.toRectF(),
                        normalizedOverlayExclusions(
                            availableReader.width,
                            availableReader.height,
                        ),
                    )
                },
                captureHandler,
            )
        }

    private fun onPixelRegionChanged(region: Rect) {
        if (captureWidth <= 0 || captureHeight <= 0) return
        if (!applyingRegionPreset) {
            activeRegionPresetName = null
            RegionPresetStore(this).activeName = null
        }
        normalizedRegion = NormalizedRegion.fromPixels(
            region = region,
            screenWidth = captureWidth,
            screenHeight = captureHeight,
        )
        lifecycle.dispatch(CaptureLifecycleEvent.RegionSelected)
        frameProcessor?.let {
            it.setEnabled(false)
            // A new crop must establish its own stable OCR candidate.
            it.resetStability()
        }
        refreshProcessorState()
    }

    /**
     * The overlay dropped the current selection. Stop recognizing the old
     * rectangle immediately: it either no longer reflects what the user wants
     * or, after a re-selection request, nothing at all.
     */
    /**
     * An expanded result panel is tall enough to overlap the selected region,
     * and the panel rectangle is masked out before OCR. Recognizing through that
     * would overwrite the long translation the user expanded in order to read.
     */
    private fun onResultsExpandedChanged(expanded: Boolean) {
        if (closing) return
        lifecycle.dispatch(CaptureLifecycleEvent.ResultsExpandedChanged(expanded))
        if (expanded) {
            frameProcessor?.setEnabled(false)
        } else {
            frameProcessor?.resetStability()
        }
        refreshProcessorState()
    }

    private fun onRegionFrozenChanged(frozen: Boolean) {
        if (closing) return
        regionFrozen = frozen
        if (frozen) {
            frameProcessor?.setEnabled(false)
        } else {
            frameProcessor?.resetStability()
        }
        refreshProcessorState()
    }

    private fun onFullScreenPausedChanged(paused: Boolean) {
        if (closing) return
        fullScreenUserPaused = paused
        if (paused) frameProcessor?.setEnabled(false)
        refreshProcessorState()
    }

    private fun applyRegionPresetRequest(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_REGION_PRESET_NAME)?.trim().orEmpty()
        if (name.isBlank()) return
        val bounds = runCatching {
            com.screentranslation.app.prefs.clampNormalizedRegion(
                left = intent.getFloatExtra(EXTRA_REGION_LEFT, 0f),
                top = intent.getFloatExtra(EXTRA_REGION_TOP, 0f),
                right = intent.getFloatExtra(EXTRA_REGION_RIGHT, 1f),
                bottom = intent.getFloatExtra(EXTRA_REGION_BOTTOM, 1f),
            )
        }.getOrNull() ?: return
        activeRegionPresetName = name
        RegionPresetStore(this).activeName = name
        applyPresetBounds(bounds)
    }

    private fun applyActivePresetForConfiguration(configuration: Configuration) {
        if (closing || captureMode != CaptureMode.REGION) return
        val store = RegionPresetStore(this)
        val name = activeRegionPresetName ?: store.activeName ?: return
        val bounds = store.load(name, RegionPresetOrientation.from(configuration)) ?: return
        activeRegionPresetName = name
        applyPresetBounds(bounds)
    }

    private fun applyPresetBounds(bounds: NormalizedRegionBounds) {
        if (captureWidth <= 0 || captureHeight <= 0) return
        val region = Rect(
            (bounds.left * captureWidth).toInt().coerceIn(0, captureWidth - 1),
            (bounds.top * captureHeight).toInt().coerceIn(0, captureHeight - 1),
            (bounds.right * captureWidth).toInt().coerceIn(1, captureWidth),
            (bounds.bottom * captureHeight).toInt().coerceIn(1, captureHeight),
        )
        applyingRegionPreset = true
        try {
            overlayController?.applySelectedRegion(region)
        } finally {
            applyingRegionPreset = false
        }
    }

    private fun onRegionCleared() {
        if (closing) return
        lifecycle.dispatch(CaptureLifecycleEvent.RegionCleared)
        normalizedRegion = NormalizedRegion.FULL
        frameProcessor?.let {
            it.setEnabled(false)
            it.resetStability()
        }
        refreshProcessorState()
    }

    private fun onRegionOverlayBoundsChanged(bounds: Rect?) {
        overlayExclusionBounds = bounds?.let { listOf(Rect(it)) }.orEmpty()
    }

    private fun onFullScreenOverlayBoundsChanged(bounds: List<Rect>) {
        overlayExclusionBounds = bounds.map(::Rect)
    }

    private fun normalizedOverlayExclusions(width: Int, height: Int): List<RectF> {
        if (width <= 0 || height <= 0) return emptyList()
        return overlayExclusionBounds.mapNotNull { source ->
            val left = source.left.coerceIn(0, width - 1)
            val top = source.top.coerceIn(0, height - 1)
            val right = source.right.coerceIn(left + 1, width)
            val bottom = source.bottom.coerceIn(top + 1, height)
            if (right <= left || bottom <= top) {
                null
            } else {
                RectF(
                    left.toFloat() / width,
                    top.toFloat() / height,
                    right.toFloat() / width,
                    bottom.toFloat() / height,
                )
            }
        }
    }

    private fun refreshProcessorState() {
        if (closing) return
        val state = lifecycle.snapshot
        currentLifecyclePhase = state.phase
        frameProcessor?.setEnabled(
            state.processorEnabled && !regionFrozen && !fullScreenUserPaused,
        )
        updateOverlayStatus(
            when {
                !state.modelReady -> STATUS_PREPARING_MODEL
                !state.regionReady -> STATUS_SELECT_REGION
                !state.screenOn -> STATUS_SCREEN_OFF
                !state.contentVisible -> STATUS_CONTENT_HIDDEN
                state.resultsExpanded -> STATUS_EXPANDED_PAUSED
                regionFrozen -> STATUS_REGION_FROZEN
                fullScreenUserPaused -> STATUS_FULL_SCREEN_PAUSED
                captureMode == CaptureMode.FULL_SCREEN_INCREMENTAL -> STATUS_FULL_SCREEN_RUNNING
                else -> STATUS_RUNNING
            },
        )
    }

    private fun startupFailureDetail(error: Throwable): String {
        val message = error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(MAX_STARTUP_ERROR_MESSAGE_CHARS)
            ?.takeIf { it.isNotEmpty() }
        return if (message == null) {
            error.javaClass.simpleName
        } else {
            "${error.javaClass.simpleName}: $message"
        }
    }

    /**
     * Keeps the existing VirtualDisplay and swaps only its size and consumer
     * surface. A MediaProjection token must not be used to create another
     * VirtualDisplay on Android 14+.
     */
    private fun resizeCaptureIfNeeded(
        requestedWidth: Int? = null,
        requestedHeight: Int? = null,
    ) {
        if (closing) return
        val display = virtualDisplay ?: return
        val oldReader = imageReader ?: return
        val currentSpec = currentCaptureSpec()
        val spec = currentSpec.copy(
            width = requestedWidth?.takeIf { it > 0 } ?: currentSpec.width,
            height = requestedHeight?.takeIf { it > 0 } ?: currentSpec.height,
        )
        if (spec.width == captureWidth &&
            spec.height == captureHeight &&
            spec.densityDpi == captureDensityDpi
        ) {
            return
        }

        val replacement = try {
            newImageReader(spec)
        } catch (error: Throwable) {
            // Without a new reader the display keeps the old surface size and no
            // further frames arrive. Say so instead of freezing on stale output.
            Log.e(TAG, "Could not allocate an ImageReader for ${spec.width}x${spec.height}", error)
            mainHandler.post {
                if (!closing) {
                    updateOverlayStatus(STATUS_RESIZE_FAILED)
                }
            }
            return
        }

        try {
            oldReader.setOnImageAvailableListener(null, null)
            display.setSurface(null)
            display.resize(spec.width, spec.height, spec.densityDpi)
            display.setSurface(replacement.surface)

            imageReader = replacement
            captureWidth = spec.width
            captureHeight = spec.height
            captureDensityDpi = spec.densityDpi

            // The region was normalized against the previous capture dimensions.
            // After a rotation the same fractions map onto a completely different
            // physical rectangle, so the selection is no longer meaningful. Drop it
            // and ask for a new one instead of silently recognizing the wrong area
            // while the overlay still reports "running".
            lifecycle.dispatch(CaptureLifecycleEvent.RotationChanged)
            normalizedRegion = NormalizedRegion.FULL
            overlayExclusionBounds = emptyList()
            frameProcessor?.setEnabled(false)
            frameProcessor?.resetStability()
            mainHandler.post {
                if (!closing) {
                    if (captureMode == CaptureMode.FULL_SCREEN_INCREMENTAL) {
                        fullScreenOverlayController?.updateBlocks(emptyList())
                    } else {
                        overlayController?.requestRegionSelection()
                    }
                    refreshProcessorState()
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Resize to ${spec.width}x${spec.height} failed; rolling back", error)
            replacement.setOnImageAvailableListener(null, null)
            replacement.close()
            try {
                display.resize(captureWidth, captureHeight, captureDensityDpi)
                display.setSurface(oldReader.surface)
                oldReader.setOnImageAvailableListener(
                    { availableReader ->
                        frameProcessor?.onImageAvailable(
                            availableReader,
                            normalizedRegion.toRectF(),
                            normalizedOverlayExclusions(
                                availableReader.width,
                                availableReader.height,
                            ),
                        )
                    },
                    captureHandler,
                )
            } catch (rollbackError: Throwable) {
                Log.e(TAG, "Rollback after a failed resize also failed; stopping", rollbackError)
                mainHandler.post { stopSelf() }
            }
            return
        }

        oldReader.close()
    }

    private fun currentCaptureSpec(): CaptureSpec {
        val bounds = getSystemService(WindowManager::class.java)
            .maximumWindowMetrics
            .bounds
        return CaptureSpec(
            width = bounds.width().coerceAtLeast(1),
            height = bounds.height().coerceAtLeast(1),
            densityDpi = resources.configuration.densityDpi.coerceAtLeast(1),
        )
    }

    private fun releaseSession() {
        if (closing) return
        closing = true
        mainHandler.removeCallbacks(performanceLogRunnable)
        if (lifecycle.snapshot.stopReason == null) {
            lifecycle.dispatch(
                CaptureLifecycleEvent.StopRequested(CaptureStopReason.SERVICE_DESTROYED),
            )
        }
        sessionStarted = false
        isRunning = false
        currentLifecyclePhase = CaptureLifecyclePhase.IDLE
        notifySessionStateChanged()

        screenReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Screen state receiver was already unregistered", error)
            }
        }
        screenReceiver = null

        imageReader?.setOnImageAvailableListener(null, null)
        frameProcessor?.close()
        virtualDisplay?.release()
        imageReader?.close()

        projectionCallback?.let { callback ->
            mediaProjection?.unregisterCallback(callback)
        }
        mediaProjection?.stop()

        translationEngine?.close()
        ocrEngine?.close()
        overlayController?.close()
        fullScreenOverlayController?.close()
        captureThread?.quitSafely()
        performanceTelemetry?.let { telemetry ->
            Log.i(PERFORMANCE_TAG, telemetry.snapshot("final").toJsonLine())
        }

        frameProcessor = null
        virtualDisplay = null
        imageReader = null
        projectionCallback = null
        mediaProjection = null
        translationEngine = null
        ocrEngine = null
        overlayController = null
        fullScreenOverlayController = null
        overlayExclusionBounds = emptyList()
        regionFrozen = false
        fullScreenUserPaused = false
        activeRegionPresetName = null
        applyingRegionPreset = false
        captureHandler = null
        captureThread = null
        performanceTelemetry = null
        lifecycle.dispatch(CaptureLifecycleEvent.Destroyed)
    }

    private fun buildNotification(): Notification {
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CaptureShortcutNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(
                getString(
                    if (captureMode == CaptureMode.FULL_SCREEN_INCREMENTAL) {
                        R.string.capture_notification_text_full_screen
                    } else {
                        R.string.capture_notification_text
                    },
                ),
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.capture_notification_stop),
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private data class CaptureSpec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    private data class NormalizedRegion(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun toRectF(): RectF = RectF(left, top, right, bottom)

        companion object {
            val FULL = NormalizedRegion(0f, 0f, 1f, 1f)

            fun fromPixels(region: Rect, screenWidth: Int, screenHeight: Int): NormalizedRegion {
                val left = region.left.coerceIn(0, screenWidth - 1)
                val top = region.top.coerceIn(0, screenHeight - 1)
                val right = region.right.coerceIn(left + 1, screenWidth)
                val bottom = region.bottom.coerceIn(top + 1, screenHeight)
                return NormalizedRegion(
                    left = left.toFloat() / screenWidth,
                    top = top.toFloat() / screenHeight,
                    right = right.toFloat() / screenWidth,
                    bottom = bottom.toFloat() / screenHeight,
                )
            }
        }
    }

    companion object {
        private const val TAG = "ScreenTranslation"
        private const val PERFORMANCE_TAG = "ScreenTranslationPerf"
        private const val PERFORMANCE_DUMP_PREFIX = "SCREEN_TRANSLATION_PERF_V1"
        private const val PERFORMANCE_LOG_INTERVAL_MS = 60_000L

        private const val ACTION_START =
            "com.screentranslation.app.action.START_SCREEN_TRANSLATION"
        private const val ACTION_STOP =
            "com.screentranslation.app.action.STOP_SCREEN_TRANSLATION"
        private const val ACTION_APPLY_REGION_PRESET =
            "com.screentranslation.app.action.APPLY_REGION_PRESET"
        internal const val ACTION_SESSION_STATE_CHANGED =
            "com.screentranslation.app.action.SESSION_STATE_CHANGED"
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val EXTRA_SOURCE_LANGUAGE = "source_language"
        private const val EXTRA_TARGET_LANGUAGE = "target_language"
        private const val EXTRA_FRAME_INTERVAL_MS = "frame_interval_ms"
        private const val EXTRA_CAPTURE_MODE = "capture_mode"
        private const val EXTRA_REGION_PRESET_NAME = "region_preset_name"
        private const val EXTRA_REGION_LEFT = "region_left"
        private const val EXTRA_REGION_TOP = "region_top"
        private const val EXTRA_REGION_RIGHT = "region_right"
        private const val EXTRA_REGION_BOTTOM = "region_bottom"

        private const val NOTIFICATION_ID = 1101
        private const val STOP_REQUEST_CODE = 1102
        private const val OPEN_APP_REQUEST_CODE = 1103
        private const val VIRTUAL_DISPLAY_NAME = "ScreenTranslationCapture"
        private const val CAPTURE_THREAD_NAME = "screen-translation-capture"
        private const val MAX_IMAGES = 2

        private const val STATUS_SELECT_REGION = "请框选需要翻译的屏幕区域"
        private const val STATUS_PREPARING_MODEL = "正在准备离线翻译模型…"
        private const val STATUS_RUNNING = "实时翻译中"
        private const val STATUS_FULL_SCREEN_RUNNING = "全屏增量识别中 · 译文覆盖原文上方"
        private const val STATUS_TRANSLATING = "正在请求在线翻译…"
        private const val STATUS_CONTENT_HIDDEN = "投屏内容暂不可见，处理已暂停"
        private const val STATUS_SCREEN_OFF = "屏幕已熄灭，处理已暂停"
        private const val STATUS_EXPANDED_PAUSED = "已展开全文，识别暂停；收起后恢复"
        private const val STATUS_REGION_FROZEN = "结果已冻结，区域识别暂停"
        private const val STATUS_FULL_SCREEN_PAUSED = "全屏识别已暂停"
        private const val STATUS_RESIZE_FAILED = "屏幕尺寸变化后无法继续采集，请重新开始"

        private const val STARTUP_STAGE_SERVICE_ENTRY = "SERVICE_ENTRY"
        private const val STARTUP_STAGE_OVERLAY = "OVERLAY"
        private const val STARTUP_STAGE_SCREEN_RECEIVER = "SCREEN_RECEIVER"
        private const val STARTUP_STAGE_CAPTURE_THREAD = "CAPTURE_THREAD"
        private const val STARTUP_STAGE_BACKENDS = "BACKENDS"
        private const val STARTUP_STAGE_FRAME_PIPELINE = "FRAME_PIPELINE"
        private const val STARTUP_STAGE_MEDIA_PROJECTION = "MEDIA_PROJECTION"
        private const val STARTUP_STAGE_IMAGE_READER = "IMAGE_READER"
        private const val STARTUP_STAGE_VIRTUAL_DISPLAY = "VIRTUAL_DISPLAY"
        private const val STARTUP_STAGE_MODEL_PREPARATION = "MODEL_PREPARATION"
        private const val MAX_STARTUP_ERROR_MESSAGE_CHARS = 160

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        internal var currentLifecyclePhase: CaptureLifecyclePhase = CaptureLifecyclePhase.IDLE
            private set

        /**
         * Process-local companion to the restart notification. It lets an
         * already-created MainActivity explain why capture ended after unlock;
         * the notification extra covers a later process recreation.
         */
        @Volatile
        var needsProjectionRestart: Boolean = false
            private set

        private data class PendingStartRequest(
            val resultCode: Int,
            val projectionData: Intent,
            val sourceLanguage: String,
            val targetLanguage: String,
            val frameIntervalMs: Long,
            val captureMode: CaptureMode,
        )

        private val pendingStartRequest = AtomicReference<PendingStartRequest?>(null)

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            sourceLanguage: String,
            targetLanguage: String,
            frameIntervalMs: Long = FrameProcessor.DEFAULT_FRAME_INTERVAL_MS,
            captureMode: CaptureMode = CaptureMode.REGION,
        ): Intent {
            val safeFrameIntervalMs = frameIntervalMs.coerceAtLeast(0L)
            pendingStartRequest.set(
                PendingStartRequest(
                    resultCode = resultCode,
                    projectionData = resultData,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    frameIntervalMs = safeFrameIntervalMs,
                    captureMode = captureMode,
                ),
            )
            return Intent(context, ScreenTranslationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, resultData)
                .putExtra(EXTRA_SOURCE_LANGUAGE, sourceLanguage)
                .putExtra(EXTRA_TARGET_LANGUAGE, targetLanguage)
                .putExtra(EXTRA_FRAME_INTERVAL_MS, safeFrameIntervalMs)
                .putExtra(EXTRA_CAPTURE_MODE, captureMode.persistedValue)
        }

        fun discardPendingStartRequest() {
            pendingStartRequest.set(null)
        }

        fun applyRegionPresetIntent(
            context: Context,
            presetName: String,
            bounds: NormalizedRegionBounds,
        ): Intent = Intent(context, ScreenTranslationService::class.java)
            .setAction(ACTION_APPLY_REGION_PRESET)
            .putExtra(EXTRA_REGION_PRESET_NAME, presetName)
            .putExtra(EXTRA_REGION_LEFT, bounds.left)
            .putExtra(EXTRA_REGION_TOP, bounds.top)
            .putExtra(EXTRA_REGION_RIGHT, bounds.right)
            .putExtra(EXTRA_REGION_BOTTOM, bounds.bottom)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenTranslationService::class.java)
                .setAction(ACTION_STOP)
    }
}
