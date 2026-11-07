package de.uditkaro.rax.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A computer running Rex that the user added. */
@Serializable
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** What the user typed: a name or IP, optionally with a port, or a full URL. */
    val address: String,
    /** If true, Rax connects to this host through its built-in Tailscale node. */
    val tailnet: Boolean = false,
)

private val Context.hostData: DataStore<Preferences> by preferencesDataStore(name = "hosts")

class HostStore(context: Context) {
    private val data = context.applicationContext.hostData
    private val key = stringPreferencesKey("hosts")
    private val json = Json { ignoreUnknownKeys = true }

    val hosts: Flow<List<Host>> = data.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString<List<Host>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun save(host: Host) = data.edit { p ->
        val list = read(p).toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list += host
        p[key] = json.encodeToString(list)
    }

    suspend fun delete(id: String) = data.edit { p -> p[key] = json.encodeToString(read(p).filterNot { it.id == id }) }

    private fun read(p: Preferences): List<Host> =
        p[key]?.let { runCatching { json.decodeFromString<List<Host>>(it) }.getOrNull() } ?: emptyList()
}
