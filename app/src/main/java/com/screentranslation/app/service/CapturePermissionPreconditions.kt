package com.screentranslation.app.service

/** Permission state evaluated immediately before requesting screen capture. */
internal data class CapturePermissionPreconditions(
    val notificationGranted: Boolean,
    val overlayGranted: Boolean,
) {
    /** Notification permission is recommended but is not a foreground-service blocker. */
    val shouldRequestNotification: Boolean
        get() = !notificationGranted

    val nextBlockingStep: CapturePermissionStep
        get() = if (overlayGranted) {
            CapturePermissionStep.REQUEST_PROJECTION
        } else {
            CapturePermissionStep.REQUEST_OVERLAY
        }
}

internal enum class CapturePermissionStep {
    REQUEST_OVERLAY,
    REQUEST_PROJECTION,
}
