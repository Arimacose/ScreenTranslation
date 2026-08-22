package com.screentranslation.app

internal enum class HomePrimaryAction {
    FIX_LANGUAGE_PAIR,
    CONFIGURE_ONLINE,
    PREPARE_MODEL,
    WAIT_FOR_MODEL,
    REQUEST_NOTIFICATION,
    REQUEST_OVERLAY,
    READY_FOR_NOTIFICATION,
    STOP_CAPTURE,
}

internal data class HomeReadinessState(
    val action: HomePrimaryAction,
    val blocked: Boolean,
)

internal data class HomeActionVisibility(
    val showPrimaryAction: Boolean,
    val showStopAction: Boolean,
)

internal fun homeActionVisibility(action: HomePrimaryAction): HomeActionVisibility = when (action) {
    HomePrimaryAction.READY_FOR_NOTIFICATION -> HomeActionVisibility(
        showPrimaryAction = false,
        showStopAction = false,
    )
    HomePrimaryAction.STOP_CAPTURE -> HomeActionVisibility(
        showPrimaryAction = false,
        showStopAction = false,
    )
    else -> HomeActionVisibility(
        showPrimaryAction = true,
        showStopAction = false,
    )
}

internal fun resolveHomeReadiness(
    serviceRunning: Boolean,
    sameLanguage: Boolean,
    onlineConfigurationReady: Boolean,
    modelTaskActive: Boolean,
    modelReady: Boolean,
    notificationGranted: Boolean,
    overlayGranted: Boolean,
): HomeReadinessState = when {
    serviceRunning -> HomeReadinessState(HomePrimaryAction.STOP_CAPTURE, blocked = false)
    sameLanguage -> HomeReadinessState(HomePrimaryAction.FIX_LANGUAGE_PAIR, blocked = true)
    !onlineConfigurationReady ->
        HomeReadinessState(HomePrimaryAction.CONFIGURE_ONLINE, blocked = false)
    modelTaskActive -> HomeReadinessState(HomePrimaryAction.WAIT_FOR_MODEL, blocked = true)
    !modelReady -> HomeReadinessState(HomePrimaryAction.PREPARE_MODEL, blocked = false)
    !notificationGranted ->
        HomeReadinessState(HomePrimaryAction.REQUEST_NOTIFICATION, blocked = false)
    !overlayGranted -> HomeReadinessState(HomePrimaryAction.REQUEST_OVERLAY, blocked = false)
    else -> HomeReadinessState(HomePrimaryAction.READY_FOR_NOTIFICATION, blocked = true)
}
