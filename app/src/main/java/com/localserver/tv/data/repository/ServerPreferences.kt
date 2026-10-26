package com.localserver.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tv_client_prefs")

class ServerPreferences(private val context: Context) {

    companion object {
        private val KEY_ACTIVE_SERVER = stringPreferencesKey("active_server_url")
        private val KEY_RECENT_SERVERS = stringPreferencesKey("recent_servers_csv")
        private const val PREFIX_BOOKMARK = "bookmark_"
        private const val PREFIX_DURATION = "duration_"
    }

    val activeServerFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_SERVER]
    }

    val recentServersFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_RECENT_SERVERS] ?: ""
        if (raw.isBlank()) emptyList()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun getActiveServer(): String? {
        return activeServerFlow.firstOrNull()
    }

    suspend fun getRecentServers(): List<String> {
        return recentServersFlow.firstOrNull() ?: emptyList()
    }

    suspend fun setActiveServer(url: String) {
        val trimmed = url.trim().trimEnd('/')
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_SERVER] = trimmed

            // Update recent servers list (up to 5 most recent)
            val currentList = (prefs[KEY_RECENT_SERVERS] ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(trimmed, ignoreCase = true) }
                .toMutableList()

            currentList.add(0, trimmed)
            val capped = currentList.take(5)
            prefs[KEY_RECENT_SERVERS] = capped.joinToString(",")
        }
    }

    suspend fun savePlaybackBookmark(mediaKey: String, positionMs: Long, durationMs: Long) {
        context.dataStore.edit { prefs ->
            val posKey = longPreferencesKey(PREFIX_BOOKMARK + mediaKey.hashCode())
            val durKey = longPreferencesKey(PREFIX_DURATION + mediaKey.hashCode())
            if (positionMs <= 10_000L || (durationMs > 0 && positionMs >= durationMs - 15_000L)) {
                // Video finished or near beginning, clear bookmark
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
}
