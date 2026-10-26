package com.localserver.tv.data.api

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Serializable
data class ShareDto(
    val id: String,
    val name: String,
    val uri: String = "",
    val isAccessible: Boolean = true
)

@Serializable
data class MediaItemDto(
    val name: String,
    val path: String,
    val isDirectory: Boolean = false,
    val size: Long = 0L,
    val mimeType: String = "",
    val modified: Long = 0L
) {
    val isVideo: Boolean
        get() {
            if (isDirectory) return false
            val mime = mimeType.lowercase()
            if (mime.startsWith("video/")) return true
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "3gp", "flv")
        }

    val isAudio: Boolean
        get() {
            if (isDirectory) return false
            val mime = mimeType.lowercase()
            if (mime.startsWith("audio/")) return true
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma")
        }

    val isImage: Boolean
        get() {
            if (isDirectory) return false
            val mime = mimeType.lowercase()
            if (mime.startsWith("image/")) return true
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
        }

    val formattedSize: String
        get() {
            if (isDirectory || size <= 0) return ""
            val kb = size / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.0f KB", kb)
                else -> "$size B"
            }
        }

    fun buildStreamUrl(serverBaseUrl: String, shareId: String): String {
        val base = serverBaseUrl.trimEnd('/')
        val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val encodedShare = URLEncoder.encode(shareId, StandardCharsets.UTF_8.name())
        return "$base/api/stream?shareId=$encodedShare&path=$encodedPath"
    }
}

@Serializable
data class MediaListDto(
    val shareId: String,
    val shareName: String = "",
    val currentPath: String = "",
    val items: List<MediaItemDto> = emptyList()
)
