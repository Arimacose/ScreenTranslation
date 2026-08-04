package com.screentranslation.app.service

import com.screentranslation.app.model.CaptureMode

/**
 * Pure state machine for the capture session signals owned by Android callbacks.
 *
 * The service still owns platform resources such as MediaProjection and overlay
 * windows. This model decides whether frame processing may run and records the
 * reason a session stopped, so instrumentation can exercise every callback seam
 * without fabricating a system MediaProjection token.
 */
internal class CaptureLifecycleStateMachine {
    @Volatile
    var snapshot: CaptureLifecycleSnapshot = CaptureLifecycleSnapshot()
        private set

    @Synchronized
    fun dispatch(event: CaptureLifecycleEvent): CaptureLifecycleSnapshot {
        snapshot = reduce(snapshot, event)
        return snapshot
    }

    companion object {
        internal fun reduce(
            current: CaptureLifecycleSnapshot,
            event: CaptureLifecycleEvent,
        ): CaptureLifecycleSnapshot {
            val updated = when (event) {
                is CaptureLifecycleEvent.StartRequested -> CaptureLifecycleSnapshot(
                    captureMode = event.captureMode,
                    regionReady = event.captureMode == CaptureMode.FULL_SCREEN_INCREMENTAL,
                    screenOn = event.screenOn,
                    phase = CaptureLifecyclePhase.STARTING,
                )

                CaptureLifecycleEvent.OverlayReady -> current.copy(overlayReady = true)
                CaptureLifecycleEvent.ProjectionReady -> current.copy(projectionReady = true)
                CaptureLifecycleEvent.ModelReady -> current.copy(modelReady = true)
                CaptureLifecycleEvent.ModelUnavailable -> current.copy(modelReady = false)
                CaptureLifecycleEvent.RegionSelected -> current.copy(regionReady = true)
                CaptureLifecycleEvent.RegionCleared -> current.copy(regionReady = false)
                is CaptureLifecycleEvent.ScreenStateChanged ->
                    current.copy(screenOn = event.screenOn)

                is CaptureLifecycleEvent.ContentVisibilityChanged ->
                    current.copy(contentVisible = event.visible)

                is CaptureLifecycleEvent.ResultsExpandedChanged ->
                    current.copy(resultsExpanded = event.expanded)

                CaptureLifecycleEvent.RotationChanged -> current.copy(
                    regionReady = if (current.captureMode == CaptureMode.REGION) {
                        false
                    } else {
                        current.regionReady
                    },
                    rotationGeneration = current.rotationGeneration + 1,
                )

                CaptureLifecycleEvent.TaskRemoved -> current.copy(taskRemovalObserved = true)
                CaptureLifecycleEvent.ProjectionRevoked -> current.copy(
                    projectionReady = false,
                    phase = CaptureLifecyclePhase.REAUTHORIZATION_REQUIRED,
                    stopReason = CaptureStopReason.PROJECTION_REVOKED,
                )

                is CaptureLifecycleEvent.StopRequested -> current.copy(
                    phase = CaptureLifecyclePhase.STOPPING,
                    stopReason = current.stopReason ?: event.reason,
                )

                CaptureLifecycleEvent.Destroyed -> current.copy(
                    phase = CaptureLifecyclePhase.STOPPED,
                    stopReason = current.stopReason ?: CaptureStopReason.SERVICE_DESTROYED,
                )
            }
            return updated.withDerivedPhase()
        }

        private fun CaptureLifecycleSnapshot.withDerivedPhase(): CaptureLifecycleSnapshot {
            if (
                phase == CaptureLifecyclePhase.STOPPING ||
                phase == CaptureLifecyclePhase.STOPPED ||
                phase == CaptureLifecyclePhase.REAUTHORIZATION_REQUIRED
            ) {
                return this
            }
            val derived = when {
                captureMode == null -> CaptureLifecyclePhase.IDLE
                !overlayReady || !projectionReady || !modelReady ->
                    CaptureLifecyclePhase.STARTING

                !regionReady -> CaptureLifecyclePhase.WAITING_FOR_REGION
                !screenOn -> CaptureLifecyclePhase.PAUSED_SCREEN_OFF
                !contentVisible -> CaptureLifecyclePhase.PAUSED_CONTENT_HIDDEN
                resultsExpanded -> CaptureLifecyclePhase.PAUSED_RESULTS_EXPANDED
                else -> CaptureLifecyclePhase.RUNNING
            }
            return copy(phase = derived)
        }
    }
}

internal data class CaptureLifecycleSnapshot(
    val phase: CaptureLifecyclePhase = CaptureLifecyclePhase.IDLE,
    val captureMode: CaptureMode? = null,
    val overlayReady: Boolean = false,
    val projectionReady: Boolean = false,
    val modelReady: Boolean = false,
    val regionReady: Boolean = false,
    val screenOn: Boolean = true,
    val contentVisible: Boolean = true,
    val resultsExpanded: Boolean = false,
    val rotationGeneration: Int = 0,
    val taskRemovalObserved: Boolean = false,
    val stopReason: CaptureStopReason? = null,
) {
    val processorEnabled: Boolean
        get() = phase == CaptureLifecyclePhase.RUNNING

    val requiresFreshProjectionConsent: Boolean
        get() = stopReason == CaptureStopReason.PROJECTION_REVOKED
}

internal enum class CaptureLifecyclePhase {
    IDLE,
    STARTING,
    WAITING_FOR_REGION,
    RUNNING,
    PAUSED_SCREEN_OFF,
    PAUSED_CONTENT_HIDDEN,
    PAUSED_RESULTS_EXPANDED,
    REAUTHORIZATION_REQUIRED,
    STOPPING,
    STOPPED,
}

internal enum class CaptureStopReason {
    USER,
    PROJECTION_REVOKED,
    STARTUP_FAILURE,
    SERVICE_DESTROYED,
}

internal sealed interface CaptureLifecycleEvent {
    data class StartRequested(
        val captureMode: CaptureMode,
        val screenOn: Boolean,
    ) : CaptureLifecycleEvent

    data object OverlayReady : CaptureLifecycleEvent
    data object ProjectionReady : CaptureLifecycleEvent
    data object ModelReady : CaptureLifecycleEvent
    data object ModelUnavailable : CaptureLifecycleEvent
    data object RegionSelected : CaptureLifecycleEvent
    data object RegionCleared : CaptureLifecycleEvent
    data class ScreenStateChanged(val screenOn: Boolean) : CaptureLifecycleEvent
    data class ContentVisibilityChanged(val visible: Boolean) : CaptureLifecycleEvent
    data class ResultsExpandedChanged(val expanded: Boolean) : CaptureLifecycleEvent
    data object RotationChanged : CaptureLifecycleEvent
    data object TaskRemoved : CaptureLifecycleEvent
    data object ProjectionRevoked : CaptureLifecycleEvent
    data class StopRequested(val reason: CaptureStopReason) : CaptureLifecycleEvent
    data object Destroyed : CaptureLifecycleEvent
}
