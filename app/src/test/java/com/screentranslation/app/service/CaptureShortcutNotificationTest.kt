package com.screentranslation.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureShortcutNotificationTest {
    @Test
    fun `shortcut is persistent only when notification access exists and capture is stopped`() {
        assertTrue(
            shouldShowCaptureShortcut(
                notificationPermissionGranted = true,
                serviceRunning = false,
            ),
        )
        assertFalse(
            shouldShowCaptureShortcut(
                notificationPermissionGranted = false,
                serviceRunning = false,
            ),
        )
        assertFalse(
            shouldShowCaptureShortcut(
                notificationPermissionGranted = true,
                serviceRunning = true,
            ),
        )
        assertFalse(
            shouldShowCaptureShortcut(
                notificationPermissionGranted = true,
                serviceRunning = false,
                idleShortcutEnabled = false,
            ),
        )
    }
}
