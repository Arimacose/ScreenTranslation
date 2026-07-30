package com.screentranslation.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryPolicyUiStateTest {

    @Test
    fun `absence from AOSP allowlist does not mean HyperOS policy is restricted`() {
        assertEquals(
            BatteryPolicyUiState.VENDOR_POLICY_UNVERIFIED,
            resolveBatteryPolicyUiState(
                isBackgroundRestricted = false,
                isAospPowerAllowlisted = false,
            ),
        )
    }

    @Test
    fun `explicit Android background restriction is reported as restricted`() {
        assertEquals(
            BatteryPolicyUiState.BACKGROUND_RESTRICTED,
            resolveBatteryPolicyUiState(
                isBackgroundRestricted = true,
                isAospPowerAllowlisted = false,
            ),
        )
    }

    @Test
    fun `AOSP allowlist membership is reported when present`() {
        assertEquals(
            BatteryPolicyUiState.AOSP_POWER_ALLOWLISTED,
            resolveBatteryPolicyUiState(
                isBackgroundRestricted = false,
                isAospPowerAllowlisted = true,
            ),
        )
    }

    @Test
    fun `missing system services fall back to unverifiable vendor policy`() {
        assertEquals(
            BatteryPolicyUiState.VENDOR_POLICY_UNVERIFIED,
            resolveBatteryPolicyUiState(
                isBackgroundRestricted = null,
                isAospPowerAllowlisted = null,
            ),
        )
    }
}
