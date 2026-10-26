package com.localserver.tv.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class MediaServerClient(
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun checkHealth(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeUrl(baseUrl)
            val request = Request.Builder()
                .url("$normalized/api/shares")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getShares(baseUrl: String): Result<List<ShareDto>> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeUrl(baseUrl)
            val request = Request.Builder()
                .url("$normalized/api/shares")
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server error HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))
                val shares = json.decodeFromString<List<ShareDto>>(body)
                Result.success(shares)
            }
        } catch (e: Exception) {
            Log.e("MediaServerClient", "Error fetching shares from $baseUrl", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches aggregated media library items directly from GET /api/media
     */
    suspend fun getAllMedia(baseUrl: String, type: String = "all"): Result<List<MediaItemDto>> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeUrl(baseUrl)
            val urlWithParams = "$normalized/api/media".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("type", type)
                ?.build()
                ?: return@withContext Result.failure(IOException("Invalid URL"))

            val request = Request.Builder()
                .url(urlWithParams)
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server error HTTP ${response.code}"))
                }
                val body = response.body?.string()?.trim()
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                val items = parseMediaItemsFlexible(body)
                Result.success(items)
            }
        } catch (e: Exception) {
            Log.e("MediaServerClient", "Error fetching /api/media from $baseUrl", e)
            Result.failure(e)
        }
    }

    /**
     * Browses a specific share directory via GET /api/shares/{shareId}/browse or GET /api/media?shareId=...
     */
    suspend fun getMedia(
        baseUrl: String,
        shareId: String,
        path: String = ""
    ): Result<MediaListDto> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeUrl(baseUrl)

            // Try /api/shares/{shareId}/browse first
            val browseUrl = "$normalized/api/shares/$shareId/browse".toHttpUrlOrNull()
                ?.newBuilder()
                ?.apply {
                    if (path.isNotEmpty()) addQueryParameter("path", path)
                }
                ?.build()

            var items: List<MediaItemDto>? = null
            if (browseUrl != null) {
                val req = Request.Builder().url(browseUrl).header("Accept", "application/json").get().build()
                runCatching {
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val b = resp.body?.string()?.trim()
                            if (!b.isNullOrEmpty()) {
                                items = parseMediaItemsFlexible(b)
                            }
                        }
                    }
                }
            }

            // Fallback to /api/media?shareId={shareId}&path={path}
            if (items == null) {
                val mediaUrl = "$normalized/api/media".toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("shareId", shareId)
                    ?.apply {
                        if (path.isNotEmpty()) addQueryParameter("path", path)
                    }
                    ?.build()
                    ?: return@withContext Result.failure(IOException("Invalid URL"))

                val req = Request.Builder().url(mediaUrl).header("Accept", "application/json").get().build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                    }
                    val b = resp.body?.string()?.trim()
                        ?: return@withContext Result.failure(IOException("Empty body"))
                    items = parseMediaItemsFlexible(b)
                }
            }

            val finalItems = items ?: emptyList()
            Result.success(MediaListDto(shareId = shareId, currentPath = path, items = finalItems))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseMediaItemsFlexible(jsonText: String): List<MediaItemDto> {
        val trimmed = jsonText.trim()
        return if (trimmed.startsWith("[")) {
            json.decodeFromString<List<MediaItemDto>>(trimmed)
        } else if (trimmed.startsWith("{")) {
            val listDto = json.decodeFromString<MediaListDto>(trimmed)
            listDto.items
        } else {
            emptyList()
        }
    }

    companion object {
        fun normalizeUrl(rawUrl: String): String {
            var url = rawUrl.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }
    }
}
