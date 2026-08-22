package com.screentranslation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReadinessTest {

    @Test
    fun `ready state hides both in-app actions`() {
        assertEquals(
            HomeActionVisibility(showPrimaryAction = false, showStopAction = false),
            homeActionVisibility(HomePrimaryAction.READY_FOR_NOTIFICATION),
        )
    }

    @Test
    fun `running state leaves stop control to the translation overlay`() {
        assertEquals(
            HomeActionVisibility(showPrimaryAction = false, showStopAction = false),
            homeActionVisibility(HomePrimaryAction.STOP_CAPTURE),
        )
    }

    @Test
    fun `setup states show only primary action`() {
        HomePrimaryAction.entries
            .filterNot {
                it == HomePrimaryAction.READY_FOR_NOTIFICATION ||
                    it == HomePrimaryAction.STOP_CAPTURE
            }
            .forEach { action ->
                assertEquals(
                    HomeActionVisibility(showPrimaryAction = true, showStopAction = false),
                    homeActionVisibility(action),
                )
            }
    }
    @Test
    fun `readiness follows task-first gate order`() {
        assertState(HomePrimaryAction.STOP_CAPTURE, false, serviceRunning = true)
        assertState(HomePrimaryAction.FIX_LANGUAGE_PAIR, true, sameLanguage = true)
        assertState(
            HomePrimaryAction.CONFIGURE_ONLINE,
            false,
            onlineConfigurationReady = false,
        )
        assertState(HomePrimaryAction.WAIT_FOR_MODEL, true, modelTaskActive = true)
        assertState(HomePrimaryAction.PREPARE_MODEL, false, modelReady = false)
        assertState(
            HomePrimaryAction.REQUEST_NOTIFICATION,
            false,
            notificationGranted = false,
        )
        assertState(HomePrimaryAction.REQUEST_OVERLAY, false, overlayGranted = false)
        assertState(HomePrimaryAction.READY_FOR_NOTIFICATION, true)
    }

    @Test
    fun `ready home state delegates capture start to notification`() {
        val state = resolveHomeReadiness(
            serviceRunning = false,
            sameLanguage = false,
            onlineConfigurationReady = true,
            modelTaskActive = false,
            modelReady = true,
            notificationGranted = true,
            overlayGranted = true,
        )

        assertEquals(HomePrimaryAction.READY_FOR_NOTIFICATION, state.action)
        assertTrue(state.blocked)
    }

    @Test
    fun `earlier blocker wins over later permissions`() {
        val state = resolveHomeReadiness(
            serviceRunning = false,
            sameLanguage = false,
            onlineConfigurationReady = true,
            modelTaskActive = true,
            modelReady = false,
            notificationGranted = false,
            overlayGranted = false,
        )
        assertEquals(HomePrimaryAction.WAIT_FOR_MODEL, state.action)
        assertTrue(state.blocked)
    }

    private fun assertState(
        expected: HomePrimaryAction,
        blocked: Boolean,
        serviceRunning: Boolean = false,
        sameLanguage: Boolean = false,
        onlineConfigurationReady: Boolean = true,
        modelTaskActive: Boolean = false,
        modelReady: Boolean = true,
        notificationGranted: Boolean = true,
        overlayGranted: Boolean = true,
    ) {
        val state = resolveHomeReadiness(
            serviceRunning = serviceRunning,
            sameLanguage = sameLanguage,
            onlineConfigurationReady = onlineConfigurationReady,
            modelTaskActive = modelTaskActive,
            modelReady = modelReady,
            notificationGranted = notificationGranted,
            overlayGranted = overlayGranted,
        )
        assertEquals(expected, state.action)
        if (blocked) assertTrue(state.blocked) else assertFalse(state.blocked)
    }
}
