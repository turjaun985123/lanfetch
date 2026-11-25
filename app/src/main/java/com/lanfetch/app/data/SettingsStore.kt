package com.lanfetch.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val segmentsPerDownload: Int = 8,
    val maxConcurrent: Int = 3,
    val wifiOnly: Boolean = false,
    val themeMode: String = "SYSTEM",
    val language: String = "system",
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val segmentsKey = intPreferencesKey("segments")
    private val concurrencyKey = intPreferencesKey("concurrency")
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val languageKey = stringPreferencesKey("language")

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            segmentsPerDownload = prefs[segmentsKey] ?: 8,
            maxConcurrent = prefs[concurrencyKey] ?: 3,
            wifiOnly = prefs[wifiOnlyKey] ?: false,
            themeMode = prefs[themeModeKey] ?: "SYSTEM",
            language = prefs[languageKey] ?: "system",
        )
    }

    suspend fun setSegments(v: Int) = context.dataStore.edit { it[segmentsKey] = v.coerceIn(1, 16) }
    suspend fun setConcurrency(v: Int) = context.dataStore.edit { it[concurrencyKey] = v.coerceIn(1, 5) }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[wifiOnlyKey] = v }
    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[themeModeKey] = v }
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[languageKey] = v }
}
