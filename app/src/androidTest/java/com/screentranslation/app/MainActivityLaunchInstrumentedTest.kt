package com.screentranslation.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
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

        withMainActivity { activity ->
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
            assertEquals(View.GONE, stop.visibility)
        }
    }

    @Test
    fun activeVisualStyleExposesStateDescriptionsAnd48DpPrimaryTargets() {
        withMainActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val minimumTarget = (48 * density).toInt()
            listOf(
                R.id.button_start,
                R.id.button_stop,
                R.id.button_prepare_models,
                R.id.button_manage_models,
                R.id.button_style_apple,
                R.id.button_style_miuix,
                R.id.button_style_material3,
            ).forEach { id ->
                val target = activity.findViewById<android.view.View>(id)
                assertTrue("target $id is below 48dp", target.minimumHeight >= minimumTarget)
            }
            val readiness = activity.findViewById<TextView>(R.id.text_readiness_summary)
            val service = activity.findViewById<TextView>(R.id.text_service_status)
            val readinessSizeSp = readiness.textSize /
                activity.resources.displayMetrics.scaledDensity
            assertTrue("readiness text is below 18sp", readinessSizeSp >= 17.9f)
            assertEquals(
                MaterialColors.getColor(
                    readiness,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
                readiness.currentTextColor,
            )
            assertTrue(readiness.accessibilityLiveRegion != android.view.View.ACCESSIBILITY_LIVE_REGION_NONE)
            assertTrue(service.accessibilityLiveRegion != android.view.View.ACCESSIBILITY_LIVE_REGION_NONE)
            assertTrue(!readiness.stateDescription.isNullOrBlank())
            assertTrue(!service.stateDescription.isNullOrBlank())
        }
    }

    private fun withMainActivity(assertions: (MainActivity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            instrumentation.runOnMainSync { assertions(activity) }
        } finally {
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
            instrumentation.waitForIdleSync()
        }
    }
}
