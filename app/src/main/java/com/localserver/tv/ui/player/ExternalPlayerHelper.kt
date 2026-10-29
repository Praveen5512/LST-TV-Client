package com.localserver.tv.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.widget.Toast

data class ExternalAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)

object ExternalPlayerHelper {

    private const val TAG = "ExternalPlayerHelper"

    val KNOWN_PLAYER_PACKAGES = listOf(
        "org.videolan.vlc" to "VLC",
        "org.courville.nova" to "Nova Video Player",
        "com.brouken.player" to "Just Player",
        "com.mxtech.videoplayer.ad" to "MX Player",
        "com.mxtech.videoplayer.pro" to "MX Player Pro",
        "org.xbmc.kodi" to "Kodi"
    )

    fun sanitizeStreamUrl(rawUrl: String): String {
        return rawUrl.trim().replace(" ", "%20")
    }

    fun inferMimeType(fileName: String, existingMime: String): String {
        if (existingMime.isNotBlank() && existingMime != "application/octet-stream") {
            return existingMime
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "avi" -> "video/avi"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "video/*"
        }
    }

    fun openWithExternalApp(
        context: Context,
        streamUrl: String,
        title: String,
        mimeType: String
    ): Boolean {
        return try {
            val sanitized = sanitizeStreamUrl(streamUrl)
            val effectiveMime = inferMimeType(title, mimeType)
            val uri = Uri.parse(sanitized)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, effectiveMime)
                putExtra(Intent.EXTRA_TITLE, title)
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Play with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error launching external player", e)
            Toast.makeText(context, "No external player found. Install VLC or Nova Player.", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun getInstalledPlayers(context: Context): List<ExternalAppInfo> {
        val pm = context.packageManager
        val installedMap = linkedMapOf<String, ExternalAppInfo>()

        // 1. Check known video players first (for preferred ordering)
        for ((pkg, name) in KNOWN_PLAYER_PACKAGES) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                installedMap[pkg] = ExternalAppInfo(pkg, label.ifEmpty { name }, icon)
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }

        // 2. Query system for all apps handling video intent
        try {
            val testIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("http://127.0.0.1:8080/test.mp4"), "video/*")
            }
            val matches = pm.queryIntentActivities(testIntent, 0)
            for (resolveInfo in matches) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg != context.packageName && !installedMap.containsKey(pkg)) {
                    val label = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    installedMap[pkg] = ExternalAppInfo(pkg, label, icon)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying system video players", e)
        }

        return installedMap.values.toList()
    }

    fun openWithSpecificPlayer(
        context: Context,
        packageName: String,
        streamUrl: String,
        title: String,
        mimeType: String
    ): Boolean {
        return try {
            val sanitized = sanitizeStreamUrl(streamUrl)
            val effectiveMime = inferMimeType(title, mimeType)
            val uri = Uri.parse(sanitized)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(packageName)
                setDataAndType(uri, effectiveMime)
                putExtra(Intent.EXTRA_TITLE, title)
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error launching player $packageName", e)
            openWithExternalApp(context, streamUrl, title, mimeType)
        }
    }
}
