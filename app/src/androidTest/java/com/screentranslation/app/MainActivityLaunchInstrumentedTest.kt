package com.screentranslation.app

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchInstrumentedTest {
    @Test
    fun launchReflectsPermissionPreconditionsAndIdleSession() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notificationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val overlayGranted = Settings.canDrawOverlays(context)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val notificationStatus = activity.findViewById<TextView>(
                    R.id.text_notification_permission_status,
                )
                val overlayStatus = activity.findViewById<TextView>(
                    R.id.text_overlay_permission_status,
                )
                val serviceStatus = activity.findViewById<TextView>(R.id.text_service_status)
                val start = activity.findViewById<Button>(R.id.button_start)
                val stop = activity.findViewById<Button>(R.id.button_stop)

                assertEquals(
                    activity.getString(
                        if (notificationGranted) {
                            R.string.notification_granted
                        } else {
                            R.string.notification_denied
                        },
                    ),
                    notificationStatus.text.toString(),
                )
                assertEquals(
                    activity.getString(
                        if (overlayGranted) R.string.overlay_granted else R.string.overlay_denied,
                    ),
                    overlayStatus.text.toString(),
                )
                assertEquals(activity.getString(R.string.service_idle), serviceStatus.text.toString())
                assertTrue(start.isEnabled)
                assertFalse(stop.isEnabled)
            }
        }
    }
}
