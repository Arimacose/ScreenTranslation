package com.screentranslation.app.vendor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

internal enum class OverlayPermissionRoute {
    APP_SPECIFIC_OVERLAY,
    HIGHLIGHTED_APP_DETAILS,
    HIGHLIGHTED_VENDOR_EDITOR,
    OVERLAY_APP_LIST,
}

/**
 * Android R and newer ignore the package as a direct-detail selector on the
 * public overlay action, but that action still opens the dedicated overlay app
 * list. We keep the package URI and highlight key so ROMs that support focused
 * rows can bring this app into view. Older releases retain the direct package
 * detail behavior. HyperOS' overlay-specific app list and broad editor remain
 * fallbacks rather than pre-empting the platform surface.
 */
internal object OverlayPermissionNavigator {
    internal const val OVERLAY_PREFERENCE_KEY = "system_alert_window"

    internal fun routeOrder(packageScopedOverlaySupported: Boolean): List<OverlayPermissionRoute> =
        if (packageScopedOverlaySupported) {
            listOf(
                OverlayPermissionRoute.APP_SPECIFIC_OVERLAY,
                OverlayPermissionRoute.HIGHLIGHTED_APP_DETAILS,
                OverlayPermissionRoute.HIGHLIGHTED_VENDOR_EDITOR,
                OverlayPermissionRoute.OVERLAY_APP_LIST,
            )
        } else {
            listOf(
                OverlayPermissionRoute.APP_SPECIFIC_OVERLAY,
                OverlayPermissionRoute.HIGHLIGHTED_VENDOR_EDITOR,
                OverlayPermissionRoute.HIGHLIGHTED_APP_DETAILS,
                OverlayPermissionRoute.OVERLAY_APP_LIST,
            )
        }

    fun intents(
        packageName: String,
        vendorAdapter: VendorAdapter,
    ): List<Intent> {
        val packageUri = Uri.parse("package:$packageName")
        val vendorIntents = vendorAdapter.overlayPermissionIntents(packageName)
        return routeOrder(
            packageScopedOverlaySupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
        ).flatMap { route ->
            when (route) {
                OverlayPermissionRoute.APP_SPECIFIC_OVERLAY -> listOf(
                    highlighted(
                        intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
                        preferenceKey = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            OVERLAY_PREFERENCE_KEY
                        } else {
                            packageName
                        },
                    ),
                )
                OverlayPermissionRoute.HIGHLIGHTED_APP_DETAILS -> listOf(
                    highlighted(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
                        HYPER_OS_APP_PERMISSION_PREFERENCE_KEY,
                    ),
                )
                OverlayPermissionRoute.HIGHLIGHTED_VENDOR_EDITOR ->
                    vendorIntents.map { highlighted(it, packageName) }
                OverlayPermissionRoute.OVERLAY_APP_LIST -> listOf(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                )
            }
        }
    }

    private fun highlighted(
        intent: Intent,
        preferenceKey: String,
    ): Intent {
        val fragmentArguments = Bundle().apply {
            putString(SETTINGS_FRAGMENT_ARGUMENT_KEY, preferenceKey)
        }
        return intent.apply {
            putExtra(SETTINGS_SHOW_FRAGMENT_ARGUMENTS, fragmentArguments)
            putExtra(SETTINGS_FRAGMENT_ARGUMENT_KEY, preferenceKey)
        }
    }

    private const val HYPER_OS_APP_PERMISSION_PREFERENCE_KEY = "app_perm_pref"
    private const val SETTINGS_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    private const val SETTINGS_FRAGMENT_ARGUMENT_KEY = ":settings:fragment_args_key"
}

object HyperOsVendorAdapter : VendorAdapter {
    override fun overlayPermissionIntents(packageName: String): List<Intent> = listOf(
        Intent(HYPER_OS_PERMISSION_APPS_EDITOR).apply {
            setClassName(
                HYPER_OS_SECURITY_CENTER_PACKAGE,
                HYPER_OS_PERMISSION_APPS_EDITOR_ACTIVITY,
            )
            putExtra(HYPER_OS_PERMISSION_ID_EXTRA, HYPER_OS_SYSTEM_ALERT_PERMISSION_ID)
        },
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
    private const val HYPER_OS_PERMISSION_APPS_EDITOR =
        "miui.intent.action.PERMISSION_APP_EDITOR_ACTIVITY"
    private const val HYPER_OS_PERMISSION_APPS_EDITOR_ACTIVITY =
        "com.miui.permcenter.permissions.PermissionAppsEditorActivity"
    private const val HYPER_OS_PERMISSION_ID_EXTRA = "extra_permission_id"
    // HyperOS 3 PermissionManager.PERM_ID_SYSTEMALERT uses the new-architecture ID 25.
    private const val HYPER_OS_SYSTEM_ALERT_PERMISSION_ID = "25"
    private const val HYPER_OS_PACKAGE_EXTRA = "extra_pkgname"
    private const val HYPER_OS_APP_DETAILS_ACTIVITY =
        "com.miui.appmanager.ApplicationsDetailsActivity"
    private const val HYPER_OS_APP_DETAILS_PACKAGE_EXTRA = "package_name"
    private const val HYPER_OS_NO_RESTRICT_SETTING = "MILLET_NO_RESTRICT_APP"
}
