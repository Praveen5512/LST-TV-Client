package com.localserver.tv.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.localserver.tv.R
import com.localserver.tv.data.repository.ServerPreferences
import com.localserver.tv.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var preferences: ServerPreferences

    private var streamUrl: String = ""
    private var videoTitle: String = ""
    private var videoSubtitle: String = ""
    private var startPositionMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var isHudVisible = true
    private val hideHudRunnable = Runnable { hideHud() }
    private val hideSeekRunnable = Runnable { binding.tvSeekIndicator.visibility = View.GONE }

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = ServerPreferences(this)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Video Playback"
        videoSubtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)

        binding.tvVideoTitle.text = videoTitle
        binding.tvVideoMeta.text = if (videoSubtitle.isNotEmpty()) videoSubtitle else "Direct HTTP 206 Streaming"

        initializePlayer()
        scheduleHideHud()
    }

    private fun initializePlayer() {
        if (streamUrl.isEmpty()) {
            Toast.makeText(this, "Invalid stream URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(20000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
            .apply {
                binding.playerView.player = this
                binding.playerView.useController = false // Custom TV remote HUD

                val mediaItem = MediaItem.fromUri(streamUrl)
                setMediaItem(mediaItem)
                prepare()

                if (startPositionMs > 0) {
                    seekTo(startPositionMs)
                }

                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                binding.bufferingOverlay.visibility = View.VISIBLE
                            }
                            Player.STATE_READY -> {
                                binding.bufferingOverlay.visibility = View.GONE
                                updateProgress()
                            }
                            Player.STATE_ENDED -> {
                                binding.bufferingOverlay.visibility = View.GONE
                                saveBookmark(0L) // cleared on complete
                                finish()
                            }
                            Player.STATE_IDLE -> {
                                binding.bufferingOverlay.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.bufferingOverlay.visibility = View.GONE
                        Toast.makeText(
                            this@VideoPlayerActivity,
                            "Stream error: ${error.message ?: "Connection lost"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }

        handler.post(progressUpdateRunnable)
    }

    private fun updateProgress() {
        val p = player ?: return
        val current = p.currentPosition.coerceAtLeast(0L)
        val duration = p.duration.coerceAtLeast(0L)

        binding.tvCurrentPosition.text = formatMillis(current)
        binding.tvTotalDuration.text = if (duration > 0) formatMillis(duration) else "--:--"

        if (duration > 0) {
            val progressPercent = ((current * 100) / duration).toInt()
            binding.seekBarProgress.progress = progressPercent
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (!isHudVisible) {
                        showHud()
                    } else {
                        togglePlayPause()
                    }
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    showHud()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    showHud()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val step = if (event.repeatCount > 0) 30000L else 10000L
                    seekRelative(step)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val step = if (event.repeatCount > 0) -30000L else -10000L
                    seekRelative(step)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    showHud()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showTrackSelectionDialog()
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    saveCurrentPositionAndFinish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            showHud(autoHide = false)
        } else {
            p.play()
            showHud(autoHide = true)
        }
    }

    private fun seekRelative(deltaMs: Long) {
        val p = player ?: return
        val newPos = (p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L))
        p.seekTo(newPos)
        updateProgress()

        // Visual feedback indicator
        val sign = if (deltaMs > 0) "+${deltaMs / 1000}s" else "${deltaMs / 1000}s"
        binding.tvSeekIndicator.text = sign
        binding.tvSeekIndicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekRunnable)
        handler.postDelayed(hideSeekRunnable, 800)

        showHud()
    }

    private fun showTrackSelectionDialog() {
        val p = player ?: return
        val currentTracks: Tracks = p.currentTracks

        val trackNames = mutableListOf<String>()
        val trackOverrides = mutableListOf<TrackSelectionOverride?>()

        trackNames.add("Default Audio / Subtitles")
        trackOverrides.add(null)

        for (group in currentTracks.groups) {
            val type = group.type
            if (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val typeLabel = if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle"
                    val lang = format.language ?: "Und"
                    val label = format.label ?: "$typeLabel ($lang)"
                    val isSelected = group.isTrackSelected(i)
                    val prefix = if (isSelected) "✓ " else "  "
                    trackNames.add("$prefix$typeLabel: $label")
                    trackOverrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
                }
            }
        }

        AlertDialog.Builder(this, R.style.Theme_TvDialog)
            .setTitle("Audio & Subtitle Tracks")
            .setItems(trackNames.toTypedArray()) { _, which ->
                val override = trackOverrides[which]
                if (override == null) {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .clearOverrides()
                        .build()
                } else {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(override)
                        .build()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showHud(autoHide: Boolean = true) {
        isHudVisible = true
        binding.hudOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideHudRunnable)
        if (autoHide && (player?.isPlaying == true)) {
            scheduleHideHud()
        }
    }

    private fun hideHud() {
        isHudVisible = false
        binding.hudOverlay.visibility = View.GONE
    }

    private fun scheduleHideHud() {
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, 4000)
    }

    private fun saveCurrentPositionAndFinish() {
        val p = player
        if (p != null) {
            val current = p.currentPosition
            val duration = p.duration
            saveBookmark(current, duration)
        }
        finish()
    }

    private fun saveBookmark(positionMs: Long, durationMs: Long = 0L) {
        if (streamUrl.isNotEmpty()) {
            lifecycleScope.launch {
                preferences.savePlaybackBookmark(streamUrl, positionMs, durationMs)
            }
        }
    }

    private fun formatMillis(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onPause() {
        super.onPause()
        player?.currentPosition?.let { pos ->
            saveBookmark(pos, player?.duration ?: 0L)
        }
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.currentPosition?.let { pos ->
            saveBookmark(pos, player?.duration ?: 0L)
        }
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_SHARE_ID = "extra_share_id"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_START_POSITION = "extra_start_position"
    }
}
