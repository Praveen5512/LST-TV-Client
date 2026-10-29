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

    suspend fun getAllBookmarksSnapshot(): Map<String, Long> = preferences.getAllBookmarksSnapshot()

    suspend fun savePlaybackBookmark(mediaKey: String, positionMs: Long, durationMs: Long) =
        preferences.savePlaybackBookmark(mediaKey, positionMs, durationMs)

    /**
     * Aggregates media files across available shares into structured categories for the TV browse view
     */
    suspend fun aggregateBrowseContent(baseUrl: String): Result<List<CategorySection>> = withContext(Dispatchers.IO) {
        val allVideos = mutableListOf<MediaCardModel>()
        val allAudio = mutableListOf<MediaCardModel>()
        val allPhotos = mutableListOf<MediaCardModel>()
        val continueWatching = mutableListOf<MediaCardModel>()
        val folderItems = mutableListOf<MediaCardModel>()

        // 1. Try aggregated /api/media first (fast single call)
        val mediaResult = client.getAllMedia(baseUrl, "all")
        val directItems = mediaResult.getOrNull().orEmpty()

        if (directItems.isNotEmpty()) {
            for (item in directItems) {
                val streamUrl = item.buildStreamUrl(baseUrl)
                val bookmark = preferences.getPlaybackBookmark(streamUrl)
                val duration = preferences.getMediaDuration(streamUrl)

                val card = MediaCardModel(
                    title = item.name,
                    subtitle = if (item.isDirectory) "Folder" else "${item.formattedSize} • ${item.shareName.ifEmpty { "Media" }}",
                    mediaItem = item,
                    shareId = item.shareId,
                    shareName = item.shareName,
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

        // 2. Fetch shares to populate folder browsing and fallback if /api/media was empty
        val sharesResult = client.getShares(baseUrl)
        val shares = sharesResult.getOrNull().orEmpty()

        for (share in shares) {
            val shareCard = MediaCardModel(
                title = share.name,
                subtitle = "Share Folder",
                mediaItem = MediaItemDto(
                    name = share.name,
                    path = "",
                    shareId = share.id,
                    shareName = share.name,
                    isDirectory = true
                ),
                shareId = share.id,
                shareName = share.name,
                streamUrl = ""
            )
            if (folderItems.none { it.shareId == share.id && it.mediaItem.path.isEmpty() }) {
                folderItems.add(shareCard)
            }

            // Fallback: If /api/media didn't return files, fetch per share
            if (directItems.isEmpty()) {
                val shareMedia = client.getMedia(baseUrl, share.id, "").getOrNull()?.items.orEmpty()
                for (item in shareMedia) {
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

        if (folderItems.isNotEmpty()) {
            sections.add(CategorySection("folders", "Folders & Shares (${folderItems.size})", folderItems))
        }

        if (continueWatching.isNotEmpty()) {
            sections.add(CategorySection("recent", "Continue Watching (${continueWatching.size})", continueWatching))
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

        Result.success(sections)
    }
}
