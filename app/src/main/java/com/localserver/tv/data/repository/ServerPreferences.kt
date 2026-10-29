package com.localserver.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "tv_client_prefs")

@Serializable
data class SavedServerConnection(
    val url: String,
    val customName: String? = null,
    val lastConnected: Long = System.currentTimeMillis()
)

class ServerPreferences(private val context: Context) {

    companion object {
        private val KEY_ACTIVE_SERVER = stringPreferencesKey("active_server_url")
        private val KEY_RECENT_SERVERS = stringPreferencesKey("recent_servers_csv")
        private val KEY_SAVED_SERVERS_JSON = stringPreferencesKey("saved_servers_json")
        private val KEY_DEFAULT_VIDEO_PLAYER = stringPreferencesKey("default_video_player")
        private val KEY_ALWAYS_USE_EXTERNAL = booleanPreferencesKey("always_use_external_player")
        private val KEY_DEFAULT_EXTERNAL_PKG = stringPreferencesKey("default_external_package")
        private val KEY_DEFAULT_EXTERNAL_NAME = stringPreferencesKey("default_external_name")
        private const val PREFIX_BOOKMARK = "bookmark_"
        private const val PREFIX_DURATION = "duration_"

        private val json = Json { ignoreUnknownKeys = true }
    }

    val activeServerFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_SERVER]
    }

    val savedConnectionsFlow: Flow<List<SavedServerConnection>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_SAVED_SERVERS_JSON]
        if (!jsonStr.isNullOrBlank()) {
            try {
                json.decodeFromString<List<SavedServerConnection>>(jsonStr)
            } catch (_: Exception) {
                parseCsvFallback(prefs[KEY_RECENT_SERVERS])
            }
        } else {
            parseCsvFallback(prefs[KEY_RECENT_SERVERS])
        }
    }

    val recentServersFlow: Flow<List<String>> = savedConnectionsFlow.map { list ->
        list.map { it.url }
    }

    private fun parseCsvFallback(raw: String?): List<SavedServerConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { SavedServerConnection(url = it) }
    }

    suspend fun getActiveServer(): String? {
        return activeServerFlow.firstOrNull()
    }

    suspend fun getSavedConnections(): List<SavedServerConnection> {
        return savedConnectionsFlow.firstOrNull() ?: emptyList()
    }

    suspend fun getRecentServers(): List<String> {
        return getSavedConnections().map { it.url }
    }

    suspend fun setActiveServer(url: String, customName: String? = null) {
        val trimmed = url.trim().trimEnd('/')
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_SERVER] = trimmed

            val current = getSavedListFromPrefs(prefs).toMutableList()
            current.removeAll { it.url.equals(trimmed, ignoreCase = true) }
            current.add(0, SavedServerConnection(url = trimmed, customName = customName, lastConnected = System.currentTimeMillis()))

            val capped = current.take(10)
            prefs[KEY_SAVED_SERVERS_JSON] = json.encodeToString(capped)
            prefs[KEY_RECENT_SERVERS] = capped.joinToString(",") { it.url }
        }
    }

    suspend fun editServerConnection(oldUrl: String, newUrl: String, newCustomName: String? = null) {
        val trimmedOld = oldUrl.trim().trimEnd('/')
        val trimmedNew = newUrl.trim().trimEnd('/')
        context.dataStore.edit { prefs ->
            val current = getSavedListFromPrefs(prefs).toMutableList()
            val index = current.indexOfFirst { it.url.equals(trimmedOld, ignoreCase = true) }

            val updatedItem = if (index >= 0) {
                val existing = current[index]
                existing.copy(
                    url = trimmedNew,
                    customName = newCustomName ?: existing.customName,
                    lastConnected = System.currentTimeMillis()
                )
            } else {
                SavedServerConnection(url = trimmedNew, customName = newCustomName, lastConnected = System.currentTimeMillis())
            }

            if (index >= 0) {
                current[index] = updatedItem
            } else {
                current.add(0, updatedItem)
            }

            // Remove any duplicates with the new URL
            val deduplicated = mutableListOf<SavedServerConnection>()
            for (conn in current) {
                if (deduplicated.none { it.url.equals(conn.url, ignoreCase = true) }) {
                    deduplicated.add(conn)
                }
            }

            prefs[KEY_SAVED_SERVERS_JSON] = json.encodeToString(deduplicated)
            prefs[KEY_RECENT_SERVERS] = deduplicated.joinToString(",") { it.url }

            // If the active server was this edited connection, update active server as well
            val active = prefs[KEY_ACTIVE_SERVER]
            if (active != null && active.equals(trimmedOld, ignoreCase = true)) {
                prefs[KEY_ACTIVE_SERVER] = trimmedNew
            }
        }
    }

    suspend fun deleteServerConnection(url: String) {
        val trimmed = url.trim().trimEnd('/')
        context.dataStore.edit { prefs ->
            val current = getSavedListFromPrefs(prefs).toMutableList()
            current.removeAll { it.url.equals(trimmed, ignoreCase = true) }

            prefs[KEY_SAVED_SERVERS_JSON] = json.encodeToString(current)
            prefs[KEY_RECENT_SERVERS] = current.joinToString(",") { it.url }

            // If the deleted server was currently active, clear it or assign to next available
            val active = prefs[KEY_ACTIVE_SERVER]
            if (active != null && active.equals(trimmed, ignoreCase = true)) {
                prefs[KEY_ACTIVE_SERVER] = current.firstOrNull()?.url ?: ""
            }
        }
    }

    suspend fun clearActiveServer() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_ACTIVE_SERVER)
        }
    }

    private fun getSavedListFromPrefs(prefs: androidx.datastore.preferences.core.Preferences): List<SavedServerConnection> {
        val jsonStr = prefs[KEY_SAVED_SERVERS_JSON]
        if (!jsonStr.isNullOrBlank()) {
            try {
                return json.decodeFromString<List<SavedServerConnection>>(jsonStr)
            } catch (_: Exception) {}
        }
        return parseCsvFallback(prefs[KEY_RECENT_SERVERS])
    }

    suspend fun savePlaybackBookmark(mediaKey: String, positionMs: Long, durationMs: Long) {
        context.dataStore.edit { prefs ->
            val posKey = longPreferencesKey(PREFIX_BOOKMARK + mediaKey.hashCode())
            val durKey = longPreferencesKey(PREFIX_DURATION + mediaKey.hashCode())
            if (positionMs <= 10_000L || (durationMs > 0 && positionMs >= durationMs - 15_000L)) {
                prefs.remove(posKey)
            } else {
                prefs[posKey] = positionMs
                if (durationMs > 0) prefs[durKey] = durationMs
            }
        }
    }

    suspend fun getPlaybackBookmark(mediaKey: String): Long {
        val prefs = context.dataStore.data.firstOrNull() ?: return 0L
        val posKey = longPreferencesKey(PREFIX_BOOKMARK + mediaKey.hashCode())
        return prefs[posKey] ?: 0L
    }

    suspend fun getMediaDuration(mediaKey: String): Long {
        val prefs = context.dataStore.data.firstOrNull() ?: return 0L
        val durKey = longPreferencesKey(PREFIX_DURATION + mediaKey.hashCode())
        return prefs[durKey] ?: 0L
    }

    suspend fun isExternalPlayerDefault(): Boolean {
        val prefs = context.dataStore.data.firstOrNull() ?: return false
        return prefs[KEY_ALWAYS_USE_EXTERNAL] == true || prefs[KEY_DEFAULT_VIDEO_PLAYER] == "external"
    }

    suspend fun getDefaultExternalPackage(): String? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        return prefs[KEY_DEFAULT_EXTERNAL_PKG]
    }

    suspend fun getDefaultExternalAppName(): String? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        return prefs[KEY_DEFAULT_EXTERNAL_NAME]
    }

    suspend fun setExternalPlayerDefault(isDefault: Boolean, packageName: String? = null, appName: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ALWAYS_USE_EXTERNAL] = isDefault
            prefs[KEY_DEFAULT_VIDEO_PLAYER] = if (isDefault) "external" else "builtin"
            if (packageName != null) {
                prefs[KEY_DEFAULT_EXTERNAL_PKG] = packageName
            } else if (!isDefault) {
                prefs.remove(KEY_DEFAULT_EXTERNAL_PKG)
            }
            if (appName != null) {
                prefs[KEY_DEFAULT_EXTERNAL_NAME] = appName
            } else if (!isDefault) {
                prefs.remove(KEY_DEFAULT_EXTERNAL_NAME)
            }
        }
    }

    suspend fun resetDefaultPlayer() {
        setExternalPlayerDefault(false, null, null)
    }
}
