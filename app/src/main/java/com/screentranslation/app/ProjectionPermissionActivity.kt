package com.screentranslation.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.service.CaptureShortcutNotification
import com.screentranslation.app.service.ScreenTranslationService

/**
 * Translucent activity opened from the persistent notification. Its dedicated
 * task keeps the user's target app visible before and after MediaProjection
 * consent, so starting another capture does not require returning to MainActivity.
 */
class ProjectionPermissionActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var projectionRequestLaunched = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val projectionData = result.data
        if (result.resultCode == Activity.RESULT_OK && projectionData != null) {
            startCaptureService(result.resultCode, projectionData)
        } else {
            CaptureShortcutNotification.show(this)
        }
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        projectionRequestLaunched = savedInstanceState?.getBoolean(KEY_REQUEST_LAUNCHED) == true
        if (projectionRequestLaunched) return

        if (actionIsInvalid() || ScreenTranslationService.isRunning) {
            finishAndRemoveTask()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            openMainApp(R.string.quick_start_overlay_required)
            return
        }
        if (!isOnlineConfigurationReady()) {
            openMainApp(R.string.quick_start_online_setup_required)
            return
        }

        projectionRequestLaunched = true
        CaptureShortcutNotification.cancel(this)
        runCatching {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        }.fold(
            onSuccess = projectionLauncher::launch,
            onFailure = {
                CaptureShortcutNotification.show(this)
                Toast.makeText(this, R.string.quick_start_projection_failed, Toast.LENGTH_LONG)
                    .show()
                finishAndRemoveTask()
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_REQUEST_LAUNCHED, projectionRequestLaunched)
        super.onSaveInstanceState(outState)
    }

    private fun actionIsInvalid(): Boolean = intent?.action != ACTION_QUICK_START

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        val preferences = AppPreferences(this)
        val startIntent = ScreenTranslationService.startIntent(
            context = this,
            resultCode = resultCode,
            resultData = resultData,
            sourceLanguage = preferences.sourceLanguage,
            targetLanguage = preferences.targetLanguage,
            frameIntervalMs = preferences.frameIntervalMs,
            captureMode = preferences.captureMode,
        )
        runCatching {
            ContextCompat.startForegroundService(this, startIntent)
        }.onFailure {
            CaptureShortcutNotification.show(this)
            Toast.makeText(this, R.string.quick_start_service_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun isOnlineConfigurationReady(): Boolean {
        if (!BuildConfig.ONLINE_LLM) return true
        return runCatching {
            val bridge = Class.forName(ONLINE_EDITION_BRIDGE_CLASS)
            val method = bridge.getMethod("isConfigurationReady", android.content.Context::class.java)
            method.invoke(null, this) as Boolean
        }.getOrDefault(false)
    }

    private fun openMainApp(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finishAndRemoveTask()
    }

    companion object {
        const val ACTION_QUICK_START =
            "com.screentranslation.app.action.QUICK_START_SCREEN_TRANSLATION"
        private const val KEY_REQUEST_LAUNCHED = "projection_request_launched"
        private const val ONLINE_EDITION_BRIDGE_CLASS =
            "com.screentranslation.app.online.OnlineEditionBridge"
    }
}
