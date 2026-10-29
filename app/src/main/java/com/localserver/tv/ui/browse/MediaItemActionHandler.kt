package com.localserver.tv.ui.browse

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.localserver.tv.data.repository.MediaCardModel
import com.localserver.tv.data.repository.ServerPreferences
import com.localserver.tv.ui.player.ExternalPlayerHelper
import com.localserver.tv.ui.player.SelectPlayerDialog
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

    /**
     * Requirement 3:
     * All media should and can only be played with an external player.
     * There is NO option for an internal player.
     * An external player can be set default and can be edited.
     */
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
            media.isVideo || media.isAudio -> {
                if (forceDialog) {
                    // Long-press: open SelectPlayerDialog to edit/change default player
                    openSelectPlayerDialog(card)
                } else {
                    playWithExternalPlayer(card)
                }
            }
            media.isImage -> {
                ExternalPlayerHelper.openWithExternalApp(
                    context,
                    card.streamUrl,
                    card.title,
                    card.mediaItem.mimeType
                )
            }
        }
    }

    private fun playWithExternalPlayer(card: MediaCardModel) {
        CoroutineScope(Dispatchers.Main).launch {
            val isDefaultSet = preferences.isExternalPlayerDefault()
            val defaultPkg = preferences.getDefaultExternalPackage()
            val defaultAppName = preferences.getDefaultExternalAppName()

            if (isDefaultSet && (!defaultPkg.isNullOrEmpty() || defaultAppName == "System Chooser")) {
                if (!defaultPkg.isNullOrEmpty()) {
                    ExternalPlayerHelper.openWithSpecificPlayer(
                        context,
                        defaultPkg,
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
                val appName = defaultAppName ?: defaultPkg ?: "External Player"
                Toast.makeText(
                    context,
                    "Playing in $appName (Hold OK to change player)",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // No default external player set yet -> prompt user to select and set default
                openSelectPlayerDialog(card)
            }
        }
    }

    fun openSelectPlayerDialog(card: MediaCardModel? = null, onPlayerChanged: (() -> Unit)? = null) {
        val dialog = SelectPlayerDialog.newInstance(
            url = card?.streamUrl ?: "",
            title = card?.title ?: "",
            mime = card?.mediaItem?.mimeType ?: "video/*",
            resumePos = 0L
        )
        dialog.setOnPlayerChangedListener {
            onPlayerChanged?.invoke()
        }
        dialog.show(fragmentManager, SelectPlayerDialog.TAG)
    }
}
