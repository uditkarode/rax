package de.uditkaro.rax.connection

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Rax may reach computers on the local network. From Android 17, an app that targets it
 * needs the user's permission for this; on earlier versions the permission is always there.
 */
class LocalNetwork(private val app: Application) {
    private val _granted = MutableStateFlow(check())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** Reads the permission again, after a request or a return from system settings. */
    fun refresh() {
        _granted.value = check()
    }

    private fun check() = !required || app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        val required = Build.VERSION.SDK_INT >= 37
    }
}
