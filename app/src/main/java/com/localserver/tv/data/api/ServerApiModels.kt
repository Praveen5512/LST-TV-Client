package com.localserver.tv.data.api

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Serializable
data class ShareDto(
    val id: String,
    val name: String,
    val uri: String = "",
    val createdAt: Long = 0L,
    val isAccessible: Boolean = true
)

@Serializable
data class MediaItemDto(
    val id: String = "",
    val fileId: String = "",
    val name: String = "",
    val path: String = "",
    val relativePath: String = "",
    val shareId: String = "",
    val shareName: String = "",
    val isDirectory: Boolean = false,
    val size: Long = 0L,
    val mimeType: String = "",
    val modified: Long = 0L,
    val lastModified: Long = 0L,
    val streamUrl: String = "",
    val downloadUrl: String = ""
) {
    val effectiveId: String
        get() = fileId.ifEmpty { id.ifEmpty { name } }

    val effectivePath: String
        get() {
            if (path.isNotEmpty()) return path
            if (relativePath.isNotEmpty()) return relativePath
            return if (isDirectory) "" else name
        }

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

    fun buildStreamUrl(serverBaseUrl: String, targetShareId: String = ""): String {
        val base = serverBaseUrl.trimEnd('/')
        if (streamUrl.isNotEmpty()) {
            return if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
                streamUrl
            } else {
                val rel = if (streamUrl.startsWith("/")) streamUrl else "/$streamUrl"
                "$base$rel"
            }
        }
        val sid = targetShareId.ifEmpty { shareId }
        val p = effectivePath
        val encodedPath = URLEncoder.encode(p, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val encodedShare = URLEncoder.encode(sid, StandardCharsets.UTF_8.name())
        return "$base/api/shares/$encodedShare/files/$encodedPath/stream"
    }
}

@Serializable
data class MediaListDto(
    val shareId: String = "",
    val shareName: String = "",
    val currentPath: String = "",
    val items: List<MediaItemDto> = emptyList()
)
