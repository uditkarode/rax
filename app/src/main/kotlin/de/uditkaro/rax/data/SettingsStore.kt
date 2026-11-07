package de.uditkaro.rax.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Appearance { System, Light, Dark }

data class Settings(
    val appearance: Appearance = Appearance.System,
    val lightThemeId: String? = null,
    val darkThemeId: String? = null,
    val fontId: String = DEFAULT_FONT,
    val fontSize: Float = DEFAULT_FONT_SIZE,
    val backgroundAlerts: Boolean = false,
    /** Permissions Rax has asked for at least once. Once Android stops asking, Rax sends the user to system settings. */
    val askedPermissions: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_FONT = "paper-mono"
        const val DEFAULT_FONT_SIZE = 13f
        val FONT_SIZES = 9f..24f
    }
}

private val Context.settingsData: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val data = context.applicationContext.settingsData

    private object Keys {
        val appearance = stringPreferencesKey("appearance")
        val lightTheme = stringPreferencesKey("light_theme")
        val darkTheme = stringPreferencesKey("dark_theme")
        val font = stringPreferencesKey("font")
        val fontSize = floatPreferencesKey("font_size")
        val backgroundAlerts = booleanPreferencesKey("background_alerts")
        val askedPermissions = stringSetPreferencesKey("asked_permissions")
    }

    val settings: Flow<Settings> = data.data.map { p ->
        Settings(
            appearance = p[Keys.appearance]?.let { name -> Appearance.entries.firstOrNull { it.name == name } }
                ?: Appearance.System,
            lightThemeId = p[Keys.lightTheme],
            darkThemeId = p[Keys.darkTheme],
            fontId = p[Keys.font] ?: Settings.DEFAULT_FONT,
            fontSize = (p[Keys.fontSize] ?: Settings.DEFAULT_FONT_SIZE)
                .coerceIn(Settings.FONT_SIZES.start, Settings.FONT_SIZES.endInclusive),
            backgroundAlerts = p[Keys.backgroundAlerts] ?: false,
            askedPermissions = p[Keys.askedPermissions].orEmpty(),
        )
    }

    suspend fun setAppearance(value: Appearance) = data.edit { it[Keys.appearance] = value.name }

    suspend fun setTheme(id: String, dark: Boolean) =
        data.edit { it[if (dark) Keys.darkTheme else Keys.lightTheme] = id }

    suspend fun setFont(id: String) = data.edit { it[Keys.font] = id }

    suspend fun setFontSize(size: Float) = data.edit {
        it[Keys.fontSize] = size.coerceIn(Settings.FONT_SIZES.start, Settings.FONT_SIZES.endInclusive)
    }

    suspend fun setBackgroundAlerts(on: Boolean) = data.edit { it[Keys.backgroundAlerts] = on }
    suspend fun setAsked(permission: String) = data.edit { it[Keys.askedPermissions] = it[Keys.askedPermissions].orEmpty() + permission }
}
