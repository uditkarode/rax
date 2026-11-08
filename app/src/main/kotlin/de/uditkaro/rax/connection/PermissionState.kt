package de.uditkaro.rax.connection

import android.app.Application
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Rax holds [permission]. The user can change it in system settings at any time. */
class PermissionState(private val app: Application, private val permission: String) {
    private val _granted = MutableStateFlow(check())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** Reads the permission again, after a request or a return from system settings. */
    fun refresh() {
        _granted.value = check()
    }

    private fun check() = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
