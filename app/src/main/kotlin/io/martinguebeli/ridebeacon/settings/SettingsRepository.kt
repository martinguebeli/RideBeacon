package io.martinguebeli.ridebeacon.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.martinguebeli.ridebeacon.model.BeaconSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("ridebeacon_settings")
private val KEY_SETTINGS = stringPreferencesKey("settings")

class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<BeaconSettings> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETTINGS]?.let {
            runCatching { Json.decodeFromString<BeaconSettings>(it) }.getOrNull()
        } ?: BeaconSettings()
    }

    suspend fun save(settings: BeaconSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SETTINGS] = Json.encodeToString(settings)
        }
    }
}
