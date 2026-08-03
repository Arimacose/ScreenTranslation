package com.screentranslation.app.vendor

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Capability boundary for ROM-owned settings surfaces and observable policy.
 *
 * The current product target remains HyperOS only. Keeping these calls behind
 * one boundary prevents MainActivity from accumulating more vendor-private
 * keys and activities, while making any future adapter an explicit project.
 */
interface VendorAdapter {
    fun overlayPermissionIntents(packageName: String): List<Intent>

    fun batteryPolicyIntents(packageName: String): List<Intent>

    fun isPowerPolicyUnrestricted(context: Context, packageName: String): Boolean?
}

object HyperOsVendorAdapter : VendorAdapter {
    override fun overlayPermissionIntents(packageName: String): List<Intent> = listOf(
        Intent(HYPER_OS_APP_PERMISSION_EDITOR).apply {
            setClassName(HYPER_OS_SECURITY_CENTER_PACKAGE, HYPER_OS_PERMISSION_EDITOR_ACTIVITY)
            putExtra(HYPER_OS_PACKAGE_EXTRA, packageName)
        },
    )

    override fun batteryPolicyIntents(packageName: String): List<Intent> = listOf(
        Intent().apply {
            setClassName(HYPER_OS_SECURITY_CENTER_PACKAGE, HYPER_OS_APP_DETAILS_ACTIVITY)
            putExtra(HYPER_OS_APP_DETAILS_PACKAGE_EXTRA, packageName)
        },
    )

    override fun isPowerPolicyUnrestricted(context: Context, packageName: String): Boolean? =
        parseUnrestrictedPackages(
            rawPackages = Settings.System.getString(
                context.contentResolver,
                HYPER_OS_NO_RESTRICT_SETTING,
            ),
            packageName = packageName,
        )

    internal fun parseUnrestrictedPackages(
        rawPackages: String?,
        packageName: String,
    ): Boolean? {
        if (rawPackages == null) return null
        return rawPackages.split(',').any { it.trim() == packageName }
    }

    private const val HYPER_OS_APP_PERMISSION_EDITOR =
        "miui.intent.action.APP_PERM_EDITOR"
    private const val HYPER_OS_SECURITY_CENTER_PACKAGE =
        "com.miui.securitycenter"
    private const val HYPER_OS_PERMISSION_EDITOR_ACTIVITY =
        "com.miui.permcenter.permissions.PermissionsEditorActivity"
    private const val HYPER_OS_PACKAGE_EXTRA = "extra_pkgname"
    private const val HYPER_OS_APP_DETAILS_ACTIVITY =
        "com.miui.appmanager.ApplicationsDetailsActivity"
    private const val HYPER_OS_APP_DETAILS_PACKAGE_EXTRA = "package_name"
    private const val HYPER_OS_NO_RESTRICT_SETTING = "MILLET_NO_RESTRICT_APP"
}
