package de.uditkaro.rax.connection

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Rax may post notifications. From Android 13 that is the notification permission, and
 * before it a switch in system settings. The user can change it in system settings at any time.
 */
class NotificationAccess(private val app: Application) {
    private val _allowed = MutableStateFlow(check())
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    /** Reads it again, after a request or a return from system settings. */
    fun refresh() {
        _allowed.value = check()
    }

    private fun check() = NotificationManagerCompat.from(app).areNotificationsEnabled()
}
