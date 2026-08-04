package com.screentranslation.app.service

import com.screentranslation.app.model.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLifecycleStateMachineTest {
    @Test
    fun `region session reaches running only after projection model and selection`() {
        val machine = CaptureLifecycleStateMachine()

        machine.dispatch(start(CaptureMode.REGION))
        assertEquals(CaptureLifecyclePhase.STARTING, machine.snapshot.phase)
        assertFalse(machine.snapshot.regionReady)

        machine.dispatch(CaptureLifecycleEvent.OverlayReady)
        machine.dispatch(CaptureLifecycleEvent.ProjectionReady)
        machine.dispatch(CaptureLifecycleEvent.ModelReady)
        assertEquals(CaptureLifecyclePhase.WAITING_FOR_REGION, machine.snapshot.phase)
        assertFalse(machine.snapshot.processorEnabled)

        machine.dispatch(CaptureLifecycleEvent.RegionSelected)
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)
        assertTrue(machine.snapshot.processorEnabled)

        machine.dispatch(
            CaptureLifecycleEvent.StopRequested(CaptureStopReason.USER),
        )
        assertEquals(CaptureLifecyclePhase.STOPPING, machine.snapshot.phase)
        assertEquals(CaptureStopReason.USER, machine.snapshot.stopReason)
        assertFalse(machine.snapshot.processorEnabled)

        machine.dispatch(CaptureLifecycleEvent.Destroyed)
        assertEquals(CaptureLifecyclePhase.STOPPED, machine.snapshot.phase)
    }

    @Test
    fun `full screen session pauses and resumes for every runtime gate`() {
        val machine = readyMachine(CaptureMode.FULL_SCREEN_INCREMENTAL)

        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)
        assertTrue(machine.snapshot.regionReady)

        machine.dispatch(CaptureLifecycleEvent.ScreenStateChanged(false))
        assertEquals(CaptureLifecyclePhase.PAUSED_SCREEN_OFF, machine.snapshot.phase)
        machine.dispatch(CaptureLifecycleEvent.ScreenStateChanged(true))
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)

        machine.dispatch(CaptureLifecycleEvent.ContentVisibilityChanged(false))
        assertEquals(CaptureLifecyclePhase.PAUSED_CONTENT_HIDDEN, machine.snapshot.phase)
        machine.dispatch(CaptureLifecycleEvent.ContentVisibilityChanged(true))
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)

        machine.dispatch(CaptureLifecycleEvent.ResultsExpandedChanged(true))
        assertEquals(CaptureLifecyclePhase.PAUSED_RESULTS_EXPANDED, machine.snapshot.phase)
        machine.dispatch(CaptureLifecycleEvent.ResultsExpandedChanged(false))
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)

        machine.dispatch(CaptureLifecycleEvent.TaskRemoved)
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)
        assertTrue(machine.snapshot.taskRemovalObserved)

        machine.dispatch(CaptureLifecycleEvent.RotationChanged)
        assertEquals(CaptureLifecyclePhase.RUNNING, machine.snapshot.phase)
        assertTrue(machine.snapshot.regionReady)
        assertEquals(1, machine.snapshot.rotationGeneration)
    }

    @Test
    fun `region rotation invalidates the old selection`() {
        val machine = readyMachine(CaptureMode.REGION).apply {
            dispatch(CaptureLifecycleEvent.RegionSelected)
        }

        machine.dispatch(CaptureLifecycleEvent.RotationChanged)

        assertEquals(CaptureLifecyclePhase.WAITING_FOR_REGION, machine.snapshot.phase)
        assertFalse(machine.snapshot.regionReady)
        assertFalse(machine.snapshot.processorEnabled)
        assertEquals(1, machine.snapshot.rotationGeneration)
    }

    @Test
    fun `projection revoke survives service destruction as fresh consent requirement`() {
        val machine = readyMachine(CaptureMode.FULL_SCREEN_INCREMENTAL)

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
    fun `permission preconditions separate recommendation from blocking step`() {
        val neither = CapturePermissionPreconditions(
            notificationGranted = false,
            overlayGranted = false,
        )
        assertTrue(neither.shouldRequestNotification)
        assertEquals(CapturePermissionStep.REQUEST_OVERLAY, neither.nextBlockingStep)

        val overlayOnly = neither.copy(overlayGranted = true)
        assertTrue(overlayOnly.shouldRequestNotification)
        assertEquals(CapturePermissionStep.REQUEST_PROJECTION, overlayOnly.nextBlockingStep)

        val notificationOnly = neither.copy(notificationGranted = true)
        assertFalse(notificationOnly.shouldRequestNotification)
        assertEquals(CapturePermissionStep.REQUEST_OVERLAY, notificationOnly.nextBlockingStep)

        val both = notificationOnly.copy(overlayGranted = true)
        assertFalse(both.shouldRequestNotification)
        assertEquals(CapturePermissionStep.REQUEST_PROJECTION, both.nextBlockingStep)
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
