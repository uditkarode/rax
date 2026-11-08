package de.uditkaro.rax.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

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

/** The saved hosts that could be read, and whether any part of the saved list could not. */
data class StoredHosts(val hosts: List<Host>, val damaged: Boolean)

private val hostJson = Json { ignoreUnknownKeys = true }

/** Reads the saved list one host at a time, so one host that cannot be read does not hide the others. */
fun decodeHosts(raw: String): StoredHosts {
    val items = runCatching { hostJson.parseToJsonElement(raw) as JsonArray }.getOrElse { return StoredHosts(emptyList(), damaged = true) }
    val hosts = items.mapNotNull { runCatching { hostJson.decodeFromJsonElement<Host>(it) }.getOrNull() }
    return StoredHosts(hosts, damaged = hosts.size < items.size)
}

private val Context.hostData: DataStore<Preferences> by preferencesDataStore(name = "hosts")

class HostStore(context: Context) {
    private val data = context.applicationContext.hostData
    private val key = stringPreferencesKey("hosts")

    /** The saved list as it was before a write dropped hosts that could not be read. */
    private val damagedKey = stringPreferencesKey("hosts_unreadable")

    val hosts: Flow<List<Host>> = data.data.map { p ->
        val stored = p[key]?.let(::decodeHosts) ?: return@map emptyList()
        if (stored.damaged) Log.w(TAG, "some saved hosts could not be read")
        stored.hosts
    }

    suspend fun save(host: Host) = data.edit { p ->
        val list = read(p).toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list += host
        p[key] = hostJson.encodeToString(list)
    }

    suspend fun delete(id: String) = data.edit { p -> p[key] = hostJson.encodeToString(read(p).filterNot { it.id == id }) }

    private fun read(p: MutablePreferences): List<Host> {
        val raw = p[key] ?: return emptyList()
        val stored = decodeHosts(raw)
        if (stored.damaged && p[damagedKey] == null) {
            Log.w(TAG, "keeping the saved hosts as ${damagedKey.name} before writing over them")
            p[damagedKey] = raw
        }
        return stored.hosts
    }

    private companion object {
        const val TAG = "RaxHosts"
    }
}
