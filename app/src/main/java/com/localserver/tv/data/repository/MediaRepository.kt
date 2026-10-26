package com.localserver.tv.data.repository

import com.localserver.tv.data.api.MediaItemDto
import com.localserver.tv.data.api.MediaListDto
import com.localserver.tv.data.api.MediaServerClient
import com.localserver.tv.data.api.ShareDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CategorySection(
    val id: String,
    val title: String,
    val items: List<MediaCardModel>
)

data class MediaCardModel(
    val title: String,
    val subtitle: String,
    val mediaItem: MediaItemDto,
    val shareId: String,
    val shareName: String,
    val streamUrl: String,
    val resumePositionMs: Long = 0L,
    val durationMs: Long = 0L
)

class MediaRepository(
    private val client: MediaServerClient,
    private val preferences: ServerPreferences
) {
    suspend fun getActiveServer(): String? = preferences.getActiveServer()

    suspend fun setActiveServer(url: String) = preferences.setActiveServer(url)

    suspend fun checkServerHealth(url: String): Boolean = client.checkHealth(url)

    suspend fun getShares(baseUrl: String): Result<List<ShareDto>> = client.getShares(baseUrl)

    suspend fun getMedia(baseUrl: String, shareId: String, path: String = ""): Result<MediaListDto> =
        client.getMedia(baseUrl, shareId, path)

    suspend fun getPlaybackBookmark(mediaKey: String): Long = preferences.getPlaybackBookmark(mediaKey)

    suspend fun getMediaDuration(mediaKey: String): Long = preferences.getMediaDuration(mediaKey)

    suspend fun savePlaybackBookmark(mediaKey: String, positionMs: Long, durationMs: Long) =
        preferences.savePlaybackBookmark(mediaKey, positionMs, durationMs)

    /**
     * Aggregates media files across available shares into structured categories for the TV browse view
     */
    suspend fun aggregateBrowseContent(baseUrl: String): Result<List<CategorySection>> = withContext(Dispatchers.IO) {
        val sharesResult = client.getShares(baseUrl)
        if (sharesResult.isFailure) {
            return@withContext Result.failure(sharesResult.exceptionOrNull() ?: Exception("Failed to load shares"))
        }

        val shares = sharesResult.getOrNull().orEmpty()
        val allVideos = mutableListOf<MediaCardModel>()
        val allAudio = mutableListOf<MediaCardModel>()
        val allPhotos = mutableListOf<MediaCardModel>()
        val continueWatching = mutableListOf<MediaCardModel>()
        val folderItems = mutableListOf<MediaCardModel>()

        for (share in shares) {
            val mediaResult = client.getMedia(baseUrl, share.id, "")
            if (mediaResult.isSuccess) {
                val list = mediaResult.getOrNull()?.items.orEmpty()
                for (item in list) {
                    val streamUrl = item.buildStreamUrl(baseUrl, share.id)
                    val bookmark = preferences.getPlaybackBookmark(streamUrl)
                    val duration = preferences.getMediaDuration(streamUrl)

                    val card = MediaCardModel(
                        title = item.name,
                        subtitle = if (item.isDirectory) "Folder" else "${item.formattedSize} • ${share.name}",
                        mediaItem = item,
                        shareId = share.id,
                        shareName = share.name,
                        streamUrl = streamUrl,
                        resumePositionMs = bookmark,
                        durationMs = duration
                    )

                    if (item.isDirectory) {
                        folderItems.add(card)
                    } else if (item.isVideo) {
                        allVideos.add(card)
                        if (bookmark > 0) {
                            continueWatching.add(card)
                        }
                    } else if (item.isAudio) {
                        allAudio.add(card)
                    } else if (item.isImage) {
                        allPhotos.add(card)
                    }
                }
            }
        }

        val sections = mutableListOf<CategorySection>()

        if (continueWatching.isNotEmpty()) {
            sections.add(CategorySection("recent", "Continue Watching", continueWatching))
        }

        if (allVideos.isNotEmpty()) {
            sections.add(CategorySection("videos", "All Videos (${allVideos.size})", allVideos))
        }

        if (allAudio.isNotEmpty()) {
            sections.add(CategorySection("audio", "Music & Audio (${allAudio.size})", allAudio))
        }

        if (allPhotos.isNotEmpty()) {
            sections.add(CategorySection("photos", "Photos & Wallpapers (${allPhotos.size})", allPhotos))
        }

        if (folderItems.isNotEmpty()) {
            sections.add(CategorySection("folders", "Folders & Directories", folderItems))
        }

        Result.success(sections)
    }
}
