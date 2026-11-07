package de.uditkaro.rax.ui

/** Requests that need the activity, such as permission prompts. */
class SystemActions(
    /** Asks for local network access, or opens Rax's system settings once Android stops asking. */
    val askLocalNetwork: () -> Unit,
    /** Asks to post notifications and reports whether Rax may. */
    val askNotifications: (onResult: (Boolean) -> Unit) -> Unit,
)
