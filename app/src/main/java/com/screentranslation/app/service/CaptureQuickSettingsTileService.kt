package com.screentranslation.app.service

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.screentranslation.app.MainActivity
import com.screentranslation.app.ProjectionPermissionActivity
import com.screentranslation.app.R
import com.screentranslation.app.model.preparation.ModelPreparationCoordinator
import com.screentranslation.app.prefs.AppPreferences
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal enum class CaptureTileState {
    NOT_READY,
    READY,
    RUNNING,
    PAUSED,
}

internal fun resolveCaptureTileState(
    serviceRunning: Boolean,
    lifecyclePhase: CaptureLifecyclePhase,
    modelReady: Boolean,
): CaptureTileState {
    if (!serviceRunning) {
        return if (modelReady) CaptureTileState.READY else CaptureTileState.NOT_READY
    }
    return if (
        lifecyclePhase in setOf(
            CaptureLifecyclePhase.PAUSED_SCREEN_OFF,
            CaptureLifecyclePhase.PAUSED_CONTENT_HIDDEN,
            CaptureLifecyclePhase.PAUSED_RESULTS_EXPANDED,
            CaptureLifecyclePhase.WAITING_FOR_REGION,
        )
    ) {
        CaptureTileState.PAUSED
    } else {
        CaptureTileState.RUNNING
    }
}

class CaptureQuickSettingsTileService : TileService() {
    private val readinessExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "capture-tile-readiness").apply { isDaemon = true }
    }
    private var readinessGeneration = 0
    @Volatile
    private var modelReady = false
    @Volatile
    private var destroyed = false

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
        refreshReadinessAsync()
    }

    override fun onClick() {
        super.onClick()
        when {
            ScreenTranslationService.isRunning -> {
                startService(ScreenTranslationService.stopIntent(this))
            }
            modelReady && Settings.canDrawOverlays(this) -> {
                launchAndCollapse(
                    Intent(this, ProjectionPermissionActivity::class.java)
                        .setAction(ProjectionPermissionActivity.ACTION_QUICK_START)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            else -> {
                launchAndCollapse(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
        refreshTile()
    }

    override fun onStopListening() {
        readinessGeneration += 1
        super.onStopListening()
    }

    override fun onDestroy() {
        destroyed = true
        readinessGeneration += 1
        readinessExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshReadinessAsync() {
        if (ScreenTranslationService.isRunning) return
        val generation = ++readinessGeneration
        val preferences = AppPreferences(this)
        try {
            readinessExecutor.execute {
                val ready = ModelPreparationCoordinator(this).isReady(
                    preferences.sourceLanguage,
                    preferences.targetLanguage,
                )
                mainExecutor.execute {
                    if (generation == readinessGeneration && !destroyed) {
                        modelReady = ready
                        refreshTile()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Tile teardown owns executor shutdown; no UI remains to update.
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val state = resolveCaptureTileState(
            serviceRunning = ScreenTranslationService.isRunning,
            lifecyclePhase = ScreenTranslationService.currentLifecyclePhase,
            modelReady = if (ScreenTranslationService.isRunning) true else modelReady,
        )
        tile.state = when (state) {
            CaptureTileState.RUNNING -> Tile.STATE_ACTIVE
            CaptureTileState.PAUSED,
            CaptureTileState.READY,
            -> Tile.STATE_INACTIVE
            CaptureTileState.NOT_READY -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.capture_tile_label)
        tile.subtitle = getString(
            when (state) {
                CaptureTileState.PAUSED -> R.string.capture_tile_paused
                CaptureTileState.RUNNING -> R.string.capture_tile_running
                CaptureTileState.READY -> R.string.capture_tile_ready
                CaptureTileState.NOT_READY -> R.string.capture_tile_not_ready
            },
        )
        tile.updateTile()
    }

    private fun launchAndCollapse(intent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            intent.component?.className.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pendingIntent)
    }
}
