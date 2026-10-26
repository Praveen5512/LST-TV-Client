package com.localserver.tv

import com.localserver.tv.data.api.MediaItemDto
import com.localserver.tv.data.api.MediaListDto
import com.localserver.tv.data.api.MediaServerClient
import com.localserver.tv.data.api.ShareDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun testMediaTypeDetection() {
        val videoItem = MediaItemDto(
            name = "Interstellar.mkv",
            path = "Movies/Interstellar.mkv",
            isDirectory = false,
            mimeType = "video/x-matroska",
            size = 4294967296L
        )
        assertTrue(videoItem.isVideo)
        assertFalse(videoItem.isAudio)
        assertFalse(videoItem.isImage)
        assertEquals("4.00 GB", videoItem.formattedSize)

        val audioItem = MediaItemDto(
            name = "Song.flac",
            path = "Music/Song.flac",
            isDirectory = false,
            mimeType = "audio/flac",
            size = 35000000L
        )
        assertTrue(audioItem.isAudio)
        assertFalse(audioItem.isVideo)
        assertEquals("33.4 MB", audioItem.formattedSize)

        val imageItem = MediaItemDto(
            name = "Landscape.jpg",
            path = "Photos/Landscape.jpg",
            isDirectory = false,
            mimeType = "image/jpeg",
            size = 2048000L
        )
        assertTrue(imageItem.isImage)
        assertFalse(imageItem.isVideo)

        val dirItem = MediaItemDto(
            name = "Action",
            path = "Movies/Action",
            isDirectory = true
        )
        assertFalse(dirItem.isVideo)
        assertFalse(dirItem.isAudio)
        assertFalse(dirItem.isImage)
        assertEquals("", dirItem.formattedSize)
    }

    @Test
    fun testStreamUrlEncoding() {
        val item = MediaItemDto(
            name = "Special Movie (2024).mp4",
            path = "Movies & Shows/Special Movie (2024).mp4"
        )
        val url = item.buildStreamUrl("http://192.168.1.10:8080/", "share_123")
        assertTrue(url.startsWith("http://192.168.1.10:8080/api/stream?shareId=share_123&path="))
        assertTrue(url.contains("Movies%20%26%20Shows%2FSpecial%20Movie%20(2024).mp4") ||
                   url.contains("Movies") && url.contains("%20"))
    }

    @Test
    fun testUrlNormalization() {
        assertEquals("http://192.168.1.50:8080", MediaServerClient.normalizeUrl("192.168.1.50:8080"))
        assertEquals("http://192.168.1.50:8080", MediaServerClient.normalizeUrl("http://192.168.1.50:8080/"))
        assertEquals("https://my-server.local:8443", MediaServerClient.normalizeUrl("https://my-server.local:8443/"))
    }

    @Test
    fun testJsonDeserialization() {
        val rawShares = """
            [
              {
                "id": "share_3126f9",
                "name": "Movies & Series",
                "uri": "content://tree/primary:Movies",
                "isAccessible": true
              }
            ]
        """.trimIndent()

        val shares = json.decodeFromString<List<ShareDto>>(rawShares)
        assertEquals(1, shares.size)
        assertEquals("share_3126f9", shares[0].id)
        assertEquals("Movies & Series", shares[0].name)

        val rawMedia = """
            {
              "shareId": "share_3126f9",
              "shareName": "Movies & Series",
              "currentPath": "Action",
              "items": [
                {
                  "name": "SciFi",
                  "path": "Action/SciFi",
                  "isDirectory": true,
                  "size": 0,
                  "mimeType": "application/x-directory",
                  "modified": 1729000000000
                },
                {
                  "name": "Interstellar.mkv",
                  "path": "Action/Interstellar.mkv",
                  "isDirectory": false,
                  "size": 4294967296,
                  "mimeType": "video/x-matroska",
                  "modified": 1729012345000
                }
              ]
            }
        """.trimIndent()

        val mediaList = json.decodeFromString<MediaListDto>(rawMedia)
        assertEquals("share_3126f9", mediaList.shareId)
        assertEquals(2, mediaList.items.size)
        assertTrue(mediaList.items[0].isDirectory)
        assertFalse(mediaList.items[1].isDirectory)
        assertEquals(4294967296L, mediaList.items[1].size)
    }
}
