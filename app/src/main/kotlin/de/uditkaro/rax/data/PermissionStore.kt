package de.uditkaro.rax.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What Rax has learned from Android's permission prompts. It stays out of backups, because a phone
 * that a backup is restored on has its own permissions.
 */
class PermissionStore(context: Context, private val scope: CoroutineScope) {
    private val data = PreferenceDataStoreFactory.create(
        produceFile = { File(context.applicationContext.noBackupFilesDir, "permissions.preferences_pb") },
    )
    private val askedKey = stringSetPreferencesKey("asked")
    private val refusedKey = stringSetPreferencesKey("refused")

    /** Permissions Rax has asked for at least once. */
    val asked: StateFlow<Set<String>> = set(askedKey)

    /** Permissions that Android may no longer ask for, so the next ask can open system settings. */
    val refused: StateFlow<Set<String>> = set(refusedKey)

    fun setAsked(permission: String) = add(askedKey, permission)

    fun setRefused(permission: String) = add(refusedKey, permission)

    /** Forgets a refusal once the permission is granted, since revoking it later lets Android ask again. */
    fun clearRefused(permission: String) {
        scope.launch { data.edit { it[refusedKey] = it[refusedKey].orEmpty() - permission } }
    }

    private fun set(key: Preferences.Key<Set<String>>): StateFlow<Set<String>> =
        data.data.map { it[key].orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private fun add(key: Preferences.Key<Set<String>>, permission: String) {
        scope.launch { data.edit { it[key] = it[key].orEmpty() + permission } }
    }
}
