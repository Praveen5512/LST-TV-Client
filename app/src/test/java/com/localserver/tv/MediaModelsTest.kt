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
        val itemWithStreamUrl = MediaItemDto(
            name = "Saripodhaa.Sanivaaram.2024.mkv",
            streamUrl = "/api/shares/8049bbe7/files/Saripodhaa.Sanivaaram.2024.mkv/stream"
        )
        val fullUrl = itemWithStreamUrl.buildStreamUrl("http://192.168.1.3:8080")
        assertEquals(
            "http://192.168.1.3:8080/api/shares/8049bbe7/files/Saripodhaa.Sanivaaram.2024.mkv/stream",
            fullUrl
        )

        val fallbackItem = MediaItemDto(
            name = "Special Movie.mp4",
            path = "Special Movie.mp4"
        )
        val url = fallbackItem.buildStreamUrl("http://192.168.1.10:8080/", "share_123")
        assertTrue(url.startsWith("http://192.168.1.10:8080/api/shares/share_123/files/"))
    }

    @Test
    fun testUrlNormalization() {
        assertEquals("http://192.168.1.50:8080", MediaServerClient.normalizeUrl("192.168.1.50:8080"))
        assertEquals("http://192.168.1.50:8080", MediaServerClient.normalizeUrl("http://192.168.1.50:8080/"))
        assertEquals("https://my-server.local:8443", MediaServerClient.normalizeUrl("https://my-server.local:8443/"))
    }

    @Test
    fun testJsonDeserializationDirectArray() {
        val serverArray = """
            [
                {
                    "shareId": "8049bbe7",
                    "shareName": "Movies",
                    "fileId": "Saripodhaa.mkv",
                    "name": "Saripodhaa.mkv",
                    "size": 1340689877,
                    "lastModified": 1792962487261,
                    "mimeType": "video/x-matroska",
                    "streamUrl": "/api/shares/8049bbe7/files/Saripodhaa.mkv/stream",
                    "downloadUrl": "/api/shares/8049bbe7/files/Saripodhaa.mkv/download"
                }
            ]
        """.trimIndent()

        val items = json.decodeFromString<List<MediaItemDto>>(serverArray)
        assertEquals(1, items.size)
        assertEquals("Saripodhaa.mkv", items[0].name)
        assertTrue(items[0].isVideo)
        assertEquals("http://192.168.1.3:8080/api/shares/8049bbe7/files/Saripodhaa.mkv/stream", items[0].buildStreamUrl("http://192.168.1.3:8080"))
    }

    @Test
    fun testJsonDeserializationNestedObject() {
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
