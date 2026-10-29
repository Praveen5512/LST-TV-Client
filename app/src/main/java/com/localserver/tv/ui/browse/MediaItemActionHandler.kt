package com.localserver.tv.ui.browse

import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentManager
import com.localserver.tv.data.repository.MediaCardModel
import com.localserver.tv.data.repository.ServerPreferences
import com.localserver.tv.ui.photo.PhotoSlideshowActivity
import com.localserver.tv.ui.player.AudioPlayerActivity
import com.localserver.tv.ui.player.ExternalPlayerHelper
import com.localserver.tv.ui.player.MediaActionDialog
import com.localserver.tv.ui.player.VideoPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaItemActionHandler(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    private val preferences: ServerPreferences = ServerPreferences(context),
    private val onFolderBackSelected: () -> Unit = {},
    private val onFolderSelected: (shareId: String, folderPath: String, folderTitle: String) -> Unit = { _, _, _ -> }
) {

    constructor(
        context: Context,
        fragmentManager: FragmentManager,
        preferences: ServerPreferences = ServerPreferences(context),
        onFolderSelected: (shareId: String, folderPath: String) -> Unit
    ) : this(
        context = context,
        fragmentManager = fragmentManager,
        preferences = preferences,
        onFolderBackSelected = {},
        onFolderSelected = { sid, path, _ -> onFolderSelected(sid, path) }
    )

    fun handleItemClicked(
        card: MediaCardModel,
        siblingItems: List<MediaCardModel> = emptyList(),
        forceDialog: Boolean = false
    ) {
        val media = card.mediaItem
        if (media.id == "__BACK__" || card.title.startsWith("⬅")) {
            onFolderBackSelected()
            return
        }
        when {
            media.isDirectory -> {
                onFolderSelected(card.shareId, media.effectivePath, card.title)
            }
            media.isVideo -> {
                if (forceDialog) {
                    showVideoActionDialog(card)
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        if (preferences.isExternalPlayerDefault()) {
                            val pkg = preferences.getDefaultExternalPackage()
                            val appName = preferences.getDefaultExternalAppName() ?: "External Player"
                            if (!pkg.isNullOrEmpty()) {
                                ExternalPlayerHelper.openWithSpecificPlayer(
                                    context,
                                    pkg,
                                    card.streamUrl,
                                    card.title,
                                    card.mediaItem.mimeType
                                )
                            } else {
                                ExternalPlayerHelper.openWithExternalApp(
                                    context,
                                    card.streamUrl,
                                    card.title,
                                    card.mediaItem.mimeType
                                )
                            }
                            android.widget.Toast.makeText(
                                context,
                                "Playing in $appName (Hold OK to change default)",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showVideoActionDialog(card)
                        }
                    }
                }
            }
            media.isAudio -> {
                playAudio(card, siblingItems)
            }
            media.isImage -> {
                viewPhotos(card, siblingItems)
            }
        }
    }

    fun showVideoActionDialog(card: MediaCardModel) {
        val bookmark = card.resumePositionMs
        val dialog = MediaActionDialog.newInstance(
            title = card.title,
            subtitle = card.subtitle,
            streamUrl = card.streamUrl,
            mimeType = card.mediaItem.mimeType,
            resumePos = bookmark
        ) { startPositionMs ->
            launchVideoPlayer(card, startPositionMs)
        }
        dialog.show(fragmentManager, MediaActionDialog.TAG)
    }

    private fun launchVideoPlayer(card: MediaCardModel, startPositionMs: Long) {
        val intent = Intent(context, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_STREAM_URL, ExternalPlayerHelper.sanitizeStreamUrl(card.streamUrl))
            putExtra(VideoPlayerActivity.EXTRA_TITLE, card.title)
            putExtra(VideoPlayerActivity.EXTRA_SUBTITLE, card.subtitle)
            putExtra(VideoPlayerActivity.EXTRA_MIME_TYPE, card.mediaItem.mimeType)
            putExtra(VideoPlayerActivity.EXTRA_SHARE_ID, card.shareId)
            putExtra(VideoPlayerActivity.EXTRA_FILE_PATH, card.mediaItem.effectivePath)
            putExtra(VideoPlayerActivity.EXTRA_START_POSITION, startPositionMs)
        }
        context.startActivity(intent)
    }

    private fun playAudio(selectedCard: MediaCardModel, siblingItems: List<MediaCardModel>) {
        val audioList = siblingItems.filter { it.mediaItem.isAudio }
        val finalQueue = if (audioList.isNotEmpty()) audioList else listOf(selectedCard)
        val selectedIndex = finalQueue.indexOfFirst { it.streamUrl == selectedCard.streamUrl }
            .coerceAtLeast(0)

        val urls = ArrayList(finalQueue.map { ExternalPlayerHelper.sanitizeStreamUrl(it.streamUrl) })
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

        val urls = ArrayList(finalQueue.map { ExternalPlayerHelper.sanitizeStreamUrl(it.streamUrl) })
        val titles = ArrayList(finalQueue.map { it.title })

        val intent = Intent(context, PhotoSlideshowActivity::class.java).apply {
            putStringArrayListExtra(PhotoSlideshowActivity.EXTRA_IMAGE_URLS, urls)
            putStringArrayListExtra(PhotoSlideshowActivity.EXTRA_TITLES, titles)
            putExtra(PhotoSlideshowActivity.EXTRA_START_INDEX, selectedIndex)
        }
        context.startActivity(intent)
    }
}
