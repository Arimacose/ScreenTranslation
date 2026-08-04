package com.screentranslation.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.screentranslation.app.model.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureLifecycleInstrumentedTest {
    @Test
    fun regionOverlayHappyPathStartsWaitsForSelectionAndStops() {
        val machine = CaptureLifecycleStateMachine()

        machine.dispatch(start(CaptureMode.REGION))
        machine.dispatch(CaptureLifecycleEvent.OverlayReady)
        machine.dispatch(CaptureLifecycleEvent.ProjectionReady)
        machine.dispatch(CaptureLifecycleEvent.ModelReady)
        assertEquals(CaptureLifecyclePhase.WAITING_FOR_REGION, machine.snapshot.phase)

        machine.dispatch(CaptureLifecycleEvent.RegionSelected)
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)
        assertTrue(machine.snapshot.processorEnabled)

        machine.dispatch(CaptureLifecycleEvent.RotationChanged)
        assertEquals(CaptureLifecyclePhase.WAITING_FOR_REGION, machine.snapshot.phase)
        assertFalse(machine.snapshot.regionReady)
        assertFalse(machine.snapshot.processorEnabled)

        machine.dispatch(CaptureLifecycleEvent.RegionSelected)
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)

        machine.dispatch(
            CaptureLifecycleEvent.StopRequested(CaptureStopReason.USER),
        )
        machine.dispatch(CaptureLifecycleEvent.Destroyed)
        assertEquals(CaptureLifecyclePhase.STOPPED, machine.snapshot.phase)
        assertEquals(CaptureStopReason.USER, machine.snapshot.stopReason)
        assertFalse(machine.snapshot.processorEnabled)
    }

    @Test
    fun fullScreenOverlayHappyPathRunsWithoutRegionGesture() {
        val machine = readyMachine(CaptureMode.FULL_SCREEN_INCREMENTAL)

        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)
        assertEquals(CaptureMode.FULL_SCREEN_INCREMENTAL, machine.snapshot.captureMode)
        assertTrue(machine.snapshot.regionReady)
        assertTrue(machine.snapshot.processorEnabled)

        machine.dispatch(CaptureLifecycleEvent.RotationChanged)
        machine.dispatch(CaptureLifecycleEvent.TaskRemoved)
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)
        assertTrue(machine.snapshot.regionReady)
        assertTrue(machine.snapshot.taskRemovalObserved)
        assertEquals(1, machine.snapshot.rotationGeneration)

        machine.dispatch(
            CaptureLifecycleEvent.StopRequested(CaptureStopReason.USER),
        )
        machine.dispatch(CaptureLifecycleEvent.Destroyed)
        assertEquals(CaptureLifecyclePhase.STOPPED, machine.snapshot.phase)
        assertEquals(CaptureStopReason.USER, machine.snapshot.stopReason)
        assertFalse(machine.snapshot.processorEnabled)
    }

    @Test
    fun screenAndProjectionCallbacksPauseResumeAndRequireFreshConsent() {
        val machine = readyMachine(CaptureMode.FULL_SCREEN_INCREMENTAL)

        machine.dispatch(CaptureLifecycleEvent.ScreenStateChanged(false))
        assertEquals(CaptureLifecyclePhase.PAUSED_SCREEN_OFF, machine.snapshot.phase)
        assertFalse(machine.snapshot.processorEnabled)
        machine.dispatch(CaptureLifecycleEvent.ScreenStateChanged(true))
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)

        machine.dispatch(CaptureLifecycleEvent.ContentVisibilityChanged(false))
        assertEquals(CaptureLifecyclePhase.PAUSED_CONTENT_HIDDEN, machine.snapshot.phase)
        machine.dispatch(CaptureLifecycleEvent.ContentVisibilityChanged(true))
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)

        machine.dispatch(CaptureLifecycleEvent.ResultsExpandedChanged(true))
        assertEquals(CaptureLifecyclePhase.PAUSED_RESULTS_EXPANDED, machine.snapshot.phase)
        assertFalse(machine.snapshot.processorEnabled)
        machine.dispatch(CaptureLifecycleEvent.ResultsExpandedChanged(false))
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)

        machine.dispatch(CaptureLifecycleEvent.ProjectionRevoked)
        assertEquals(
            CaptureLifecyclePhase.REAUTHORIZATION_REQUIRED,
            machine.snapshot.phase,
        )
        assertTrue(machine.snapshot.requiresFreshProjectionConsent)

        machine.dispatch(CaptureLifecycleEvent.Destroyed)
        assertEquals(CaptureLifecyclePhase.STOPPED, machine.snapshot.phase)
        assertEquals(CaptureStopReason.PROJECTION_REVOKED, machine.snapshot.stopReason)
        assertTrue(machine.snapshot.requiresFreshProjectionConsent)
    }

    @Test
    fun permissionPreconditionsKeepNotificationOptionalAndOverlayBlocking() {
        val missing = CapturePermissionPreconditions(
            notificationGranted = false,
            overlayGranted = false,
        )
        assertTrue(missing.shouldRequestNotification)
        assertEquals(CapturePermissionStep.REQUEST_OVERLAY, missing.nextBlockingStep)

        val overlayGranted = missing.copy(overlayGranted = true)
        assertTrue(overlayGranted.shouldRequestNotification)
        assertEquals(
            CapturePermissionStep.REQUEST_PROJECTION,
            overlayGranted.nextBlockingStep,
        )

        val allGranted = overlayGranted.copy(notificationGranted = true)
        assertFalse(allGranted.shouldRequestNotification)
        assertEquals(CapturePermissionStep.REQUEST_PROJECTION, allGranted.nextBlockingStep)
    }

    private fun readyMachine(captureMode: CaptureMode): CaptureLifecycleStateMachine =
        CaptureLifecycleStateMachine().apply {
            dispatch(start(captureMode))
            dispatch(CaptureLifecycleEvent.OverlayReady)
            dispatch(CaptureLifecycleEvent.ProjectionReady)
            dispatch(CaptureLifecycleEvent.ModelReady)
        }

    private fun start(captureMode: CaptureMode) =
        CaptureLifecycleEvent.StartRequested(
            captureMode = captureMode,
            screenOn = true,
        )
}
