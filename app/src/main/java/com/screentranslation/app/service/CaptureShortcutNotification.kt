package com.screentranslation.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import com.screentranslation.app.ProjectionPermissionActivity
import com.screentranslation.app.R
import com.screentranslation.app.prefs.AppPreferences

internal fun shouldShowCaptureShortcut(
    notificationPermissionGranted: Boolean,
    serviceRunning: Boolean,
    idleShortcutEnabled: Boolean = true,
): Boolean = notificationPermissionGranted && !serviceRunning && idleShortcutEnabled

/** Persistent, user-initiated entry point for starting capture over the current app. */
object CaptureShortcutNotification {
    const val CHANNEL_ID = "screen_translation_capture"
    const val NOTIFICATION_ID = 1106
    private const val START_REQUEST_CODE = 1107

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.capture_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun show(
        context: Context,
        projectionStopped: Boolean = false,
        startupFailureMessage: String? = null,
    ) {
        val appContext = context.applicationContext
        val permissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!shouldShowCaptureShortcut(
                permissionGranted,
                ScreenTranslationService.isRunning,
                AppPreferences(appContext).idleShortcutEnabled,
            )
        ) {
            cancel(appContext)
            return
        }
        ensureChannel(appContext)

        val launchIntent = Intent(appContext, ProjectionPermissionActivity::class.java)
        launchIntent.setPackage(appContext.packageName)
        launchIntent.action = ProjectionPermissionActivity.ACTION_QUICK_START
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        val launchPendingIntent = PendingIntent.getActivity(
            appContext,
            START_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                appContext.getString(
                    when {
                        startupFailureMessage != null -> R.string.capture_shortcut_start_failed_title
                        projectionStopped -> R.string.capture_shortcut_projection_stopped_title
                        else -> R.string.capture_shortcut_title
                    },
                ),
            )
            .setContentText(
                startupFailureMessage ?: appContext.getString(
                    if (projectionStopped) R.string.capture_shortcut_projection_stopped_text
                    else R.string.capture_shortcut_text,
                ),
            )
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(appContext, R.drawable.ic_notification),
                    appContext.getString(R.string.capture_shortcut_action),
                    launchPendingIntent,
                ).build(),
            )
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }
}
