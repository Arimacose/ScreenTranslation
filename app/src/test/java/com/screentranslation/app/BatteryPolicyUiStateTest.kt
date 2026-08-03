package com.screentranslation.app

import com.screentranslation.app.model.LanguageOption
import com.screentranslation.app.vendor.HyperOsVendorAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `HyperOS unlimited setting has priority over the AOSP signal`() {
        assertEquals(
            BatteryPolicyUiState.HYPER_OS_UNRESTRICTED,
            resolveBatteryPolicyUiState(
                isBackgroundRestricted = false,
                isAospPowerAllowlisted = false,
                isHyperOsUnrestricted = true,
            ),
        )
        assertEquals(
            BatteryPolicyUiState.HYPER_OS_NOT_UNRESTRICTED,
            resolveBatteryPolicyUiState(
                isBackgroundRestricted = false,
                isAospPowerAllowlisted = true,
                isHyperOsUnrestricted = false,
            ),
        )
    }

    @Test
    fun `HyperOS package list uses trimmed exact package matches`() {
        val raw = " com.example.first, com.screentranslation.app.online ,com.example.last "

        assertEquals(
            true,
            HyperOsVendorAdapter.parseUnrestrictedPackages(
                raw,
                "com.screentranslation.app.online",
            ),
        )
        assertEquals(
            false,
            HyperOsVendorAdapter.parseUnrestrictedPackages(raw, "com.screentranslation.app"),
        )
        assertEquals(
            null,
            HyperOsVendorAdapter.parseUnrestrictedPackages(null, "com.example"),
        )
    }

    @Test
    fun `Bergamot Lite exposes only measured English and Japanese routes`() {
        assertEquals(
            listOf(LanguageOption.ENGLISH, LanguageOption.JAPANESE),
            sourceOptionsForEdition(
                isBergamotLite = true,
                targetsChineseOnly = true,
            ),
        )
        assertEquals(
            listOf(LanguageOption.CHINESE_SIMPLIFIED),
            targetOptionsForEdition(targetsChineseOnly = true),
        )
    }

    @Test
    fun `Full excludes Chinese as a source when Chinese is the only target`() {
        val options = sourceOptionsForEdition(
            isBergamotLite = false,
            targetsChineseOnly = true,
        )

        assertFalse(options.contains(LanguageOption.CHINESE_SIMPLIFIED))
        assertTrue(options.contains(LanguageOption.ENGLISH))
        assertTrue(options.contains(LanguageOption.JAPANESE))
    }
}
