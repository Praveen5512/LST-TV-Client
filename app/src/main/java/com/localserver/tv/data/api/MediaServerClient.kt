package com.localserver.tv.data.api

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
            Result.failure(e)
        }
    }

    suspend fun getMedia(
        baseUrl: String,
        shareId: String,
        path: String = ""
    ): Result<MediaListDto> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeUrl(baseUrl)
            val parsedUrl = "$normalized/api/media".toHttpUrlOrNull()
                ?: return@withContext Result.failure(IOException("Invalid server URL: $baseUrl"))

            val urlWithParams = parsedUrl.newBuilder()
                .addQueryParameter("shareId", shareId)
                .apply {
                    if (path.isNotEmpty()) {
                        addQueryParameter("path", path)
                    }
                }
                .build()

            val request = Request.Builder()
                .url(urlWithParams)
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server error HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))
                val mediaList = json.decodeFromString<MediaListDto>(body)
                Result.success(mediaList)
            }
        } catch (e: Exception) {
            Result.failure(e)
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
