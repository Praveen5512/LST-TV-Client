package com.localserver.tv.ui.browse

import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentManager
import com.localserver.tv.data.repository.MediaCardModel
import com.localserver.tv.ui.photo.PhotoSlideshowActivity
import com.localserver.tv.ui.player.AudioPlayerActivity
import com.localserver.tv.ui.player.ResumePlaybackDialog
import com.localserver.tv.ui.player.VideoPlayerActivity

class MediaItemActionHandler(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    private val onFolderSelected: (shareId: String, folderPath: String) -> Unit
) {

    fun handleItemClicked(
        card: MediaCardModel,
        siblingItems: List<MediaCardModel> = emptyList()
    ) {
        val media = card.mediaItem
        when {
            media.isDirectory -> {
                onFolderSelected(card.shareId, media.path)
            }
            media.isVideo -> {
                playVideo(card)
            }
            media.isAudio -> {
                playAudio(card, siblingItems)
            }
            media.isImage -> {
                viewPhotos(card, siblingItems)
            }
        }
    }

    private fun playVideo(card: MediaCardModel) {
        val bookmark = card.resumePositionMs
        if (bookmark > 30_000L) {
            val dialog = ResumePlaybackDialog.newInstance(card.title, bookmark) { resume ->
                val startPos = if (resume) bookmark else 0L
                launchVideoPlayer(card, startPos)
            }
            dialog.show(fragmentManager, ResumePlaybackDialog.TAG)
        } else {
            launchVideoPlayer(card, 0L)
        }
    }

    private fun launchVideoPlayer(card: MediaCardModel, startPositionMs: Long) {
        val intent = Intent(context, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_STREAM_URL, card.streamUrl)
            putExtra(VideoPlayerActivity.EXTRA_TITLE, card.title)
            putExtra(VideoPlayerActivity.EXTRA_SUBTITLE, card.subtitle)
            putExtra(VideoPlayerActivity.EXTRA_SHARE_ID, card.shareId)
            putExtra(VideoPlayerActivity.EXTRA_FILE_PATH, card.mediaItem.path)
            putExtra(VideoPlayerActivity.EXTRA_START_POSITION, startPositionMs)
        }
        context.startActivity(intent)
    }

    private fun playAudio(selectedCard: MediaCardModel, siblingItems: List<MediaCardModel>) {
        val audioList = siblingItems.filter { it.mediaItem.isAudio }
        val finalQueue = if (audioList.isNotEmpty()) audioList else listOf(selectedCard)
        val selectedIndex = finalQueue.indexOfFirst { it.streamUrl == selectedCard.streamUrl }
            .coerceAtLeast(0)

        val urls = ArrayList(finalQueue.map { it.streamUrl })
        val titles = ArrayList(finalQueue.map { it.title })
        val subtitles = ArrayList(finalQueue.map { it.subtitle })

        val intent = Intent(context, AudioPlayerActivity::class.java).apply {
            putStringArrayListExtra(AudioPlayerActivity.EXTRA_URLS, urls)
            putStringArrayListExtra(AudioPlayerActivity.EXTRA_TITLES, titles)
            putStringArrayListExtra(AudioPlayerActivity.EXTRA_SUBTITLES, subtitles)
            putExtra(AudioPlayerActivity.EXTRA_START_INDEX, selectedIndex)
        }
        context.startActivity(intent)
    }

    private fun viewPhotos(selectedCard: MediaCardModel, siblingItems: List<MediaCardModel>) {
        val photoList = siblingItems.filter { it.mediaItem.isImage }
        val finalQueue = if (photoList.isNotEmpty()) photoList else listOf(selectedCard)
        val selectedIndex = finalQueue.indexOfFirst { it.streamUrl == selectedCard.streamUrl }
            .coerceAtLeast(0)

        val urls = ArrayList(finalQueue.map { it.streamUrl })
        val titles = ArrayList(finalQueue.map { it.title })

        val intent = Intent(context, PhotoSlideshowActivity::class.java).apply {
            putStringArrayListExtra(PhotoSlideshowActivity.EXTRA_IMAGE_URLS, urls)
            putStringArrayListExtra(PhotoSlideshowActivity.EXTRA_TITLES, titles)
            putExtra(PhotoSlideshowActivity.EXTRA_START_INDEX, selectedIndex)
        }
        context.startActivity(intent)
    }
}
