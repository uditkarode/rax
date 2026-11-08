package de.uditkaro.rax.ui

/**
 * Requests that need the activity, such as permission prompts. Each asks for its permission, or
 * opens Rax's system settings once Android stops asking. Screens learn the answer from the
 * permission's state in [de.uditkaro.rax.AppGraph].
 */
class SystemActions(
    val askLocalNetwork: () -> Unit,
    val askNotifications: () -> Unit,
)
