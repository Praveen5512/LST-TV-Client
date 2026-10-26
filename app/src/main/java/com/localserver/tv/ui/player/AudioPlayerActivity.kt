package com.localserver.tv.ui.player

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.localserver.tv.R
import com.localserver.tv.databinding.ActivityAudioPlayerBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioPlayerBinding
    private var player: ExoPlayer? = null
    private var discAnimator: ObjectAnimator? = null

    private var urlList = ArrayList<String>()
    private var titleList = ArrayList<String>()
    private var subtitleList = ArrayList<String>()
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateAudioProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        urlList = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        titleList = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        subtitleList = intent.getStringArrayListExtra(EXTRA_SUBTITLES) ?: arrayListOf()
        currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, (urlList.size - 1).coerceAtLeast(0))

        setupDiscAnimation()
        setupQueueRecyclerView()
        setupButtons()
        initializeAudioPlayer()
    }

    private fun setupDiscAnimation() {
        discAnimator = ObjectAnimator.ofFloat(binding.ivVinylDisc, View.ROTATION, 0f, 360f).apply {
            duration = 12000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun setupQueueRecyclerView() {
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvQueue.layoutManager = layoutManager
        binding.rvQueue.adapter = AudioQueueAdapter(titleList, currentIndex) { clickedIndex ->
            jumpToTrack(clickedIndex)
        }
        if (currentIndex > 0) {
            binding.rvQueue.scrollToPosition(currentIndex)
        }
    }

    private fun setupButtons() {
        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        binding.btnNext.setOnClickListener {
            playNextTrack()
        }
        binding.btnPrevious.setOnClickListener {
            playPreviousTrack()
        }
        binding.btnPlayPause.requestFocus()
    }

    private fun initializeAudioPlayer() {
        if (urlList.isEmpty()) {
            finish()
            return
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                val mediaItems = urlList.map { MediaItem.fromUri(it) }
                setMediaItems(mediaItems, currentIndex, 0L)
                prepare()
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentIndex = currentMediaItemIndex
                        updateTrackUI()
                        binding.rvQueue.adapter?.notifyDataSetChanged()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                            if (discAnimator?.isPaused == true) {
                                discAnimator?.resume()
                            } else if (discAnimator?.isStarted != true) {
                                discAnimator?.start()
                            }
                        } else {
                            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                            discAnimator?.pause()
                        }
                    }
                })
            }

        updateTrackUI()
        handler.post(progressRunnable)
    }

    private fun updateTrackUI() {
        val title = titleList.getOrNull(currentIndex) ?: "Track ${currentIndex + 1}"
        val sub = subtitleList.getOrNull(currentIndex) ?: "Lossless Audio"

        binding.tvTrackTitle.text = title
        binding.tvTrackArtist.text = sub
    }

    private fun updateAudioProgress() {
        val p = player ?: return
        val current = p.currentPosition.coerceAtLeast(0L)
        val duration = p.duration.coerceAtLeast(0L)

        binding.tvAudioCurrentTime.text = formatMillis(current)
        binding.tvAudioTotalTime.text = if (duration > 0) formatMillis(duration) else "--:--"

        if (duration > 0) {
            val percent = ((current * 100) / duration).toInt()
            binding.audioSeekBar.progress = percent
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun playNextTrack() {
        val p = player ?: return
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
        }
    }

    private fun playPreviousTrack() {
        val p = player ?: return
        if (p.hasPreviousMediaItem()) {
            p.seekToPreviousMediaItem()
        }
    }

    private fun jumpToTrack(index: Int) {
        val p = player ?: return
        if (index in 0 until urlList.size) {
            p.seekToDefaultPosition(index)
            p.play()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNextTrack()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    playPreviousTrack()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun formatMillis(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        discAnimator?.cancel()
        player?.release()
        player = null
    }

    class AudioQueueAdapter(
        private val titles: List<String>,
        private var selectedIdx: Int,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<AudioQueueAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNum: TextView = view.findViewById(R.id.tvTrackNumber)
            val tvTitle: TextView = view.findViewById(R.id.tvQueueTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_audio_queue_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.tvNum.text = "${position + 1}"
            holder.tvTitle.text = titles[position]

            if (position == selectedIdx) {
                holder.tvNum.setTextColor(holder.itemView.context.getColor(R.color.tv_online))
            } else {
                holder.tvNum.setTextColor(holder.itemView.context.getColor(R.color.tv_accent))
            }

            holder.itemView.setOnClickListener {
                selectedIdx = position
                notifyDataSetChanged()
                onItemClick(position)
            }
        }

        override fun getItemCount(): Int = titles.size
    }

    companion object {
        const val EXTRA_URLS = "extra_audio_urls"
        const val EXTRA_TITLES = "extra_audio_titles"
        const val EXTRA_SUBTITLES = "extra_audio_subtitles"
        const val EXTRA_START_INDEX = "extra_audio_start_index"
    }
}
