package com.localserver.tv.ui.photo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.localserver.tv.R
import com.localserver.tv.databinding.ActivityPhotoSlideshowBinding

class PhotoSlideshowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoSlideshowBinding
    private var imageUrls = ArrayList<String>()
    private var titles = ArrayList<String>()
    private var currentIndex = 0
    private var isSlideshowPlaying = true

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { binding.photoOverlay.visibility = View.GONE }
    private val nextPhotoRunnable = Runnable {
        if (isSlideshowPlaying) {
            advancePhoto(1)
            scheduleNextPhoto()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoSlideshowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imageUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS) ?: arrayListOf()
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, (imageUrls.size - 1).coerceAtLeast(0))

        if (imageUrls.isEmpty()) {
            Toast.makeText(this, "No photos available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        displayCurrentPhoto()
        scheduleNextPhoto()
        showOverlayBriefly()
    }

    private fun displayCurrentPhoto() {
        val url = imageUrls.getOrNull(currentIndex) ?: return
        val title = titles.getOrNull(currentIndex) ?: "Photo ${currentIndex + 1}"

        binding.tvPhotoTitle.text = title
        binding.tvPhotoCounter.text = "${currentIndex + 1} / ${imageUrls.size}"

        val status = if (isSlideshowPlaying) {
            getString(R.string.photo_slideshow_pause)
        } else {
            getString(R.string.photo_slideshow_play)
        }
        binding.tvPhotoStatus.text = "$status • [Left/Right] Browse"

        binding.pbPhotoLoading.visibility = View.VISIBLE
        binding.ivPhotoDisplay.load(url) {
            crossfade(400)
            listener(
                onSuccess = { _, _ ->
                    binding.pbPhotoLoading.visibility = View.GONE
                },
                onError = { _, _ ->
                    binding.pbPhotoLoading.visibility = View.GONE
                }
            )
        }
    }

    private fun advancePhoto(delta: Int) {
        if (imageUrls.isEmpty()) return
        currentIndex = (currentIndex + delta + imageUrls.size) % imageUrls.size
        displayCurrentPhoto()
        showOverlayBriefly()
    }

    private fun toggleSlideshow() {
        isSlideshowPlaying = !isSlideshowPlaying
        handler.removeCallbacks(nextPhotoRunnable)
        if (isSlideshowPlaying) {
            scheduleNextPhoto()
        }
        displayCurrentPhoto()
        showOverlayBriefly()
    }

    private fun scheduleNextPhoto() {
        handler.removeCallbacks(nextPhotoRunnable)
        if (isSlideshowPlaying) {
            handler.postDelayed(nextPhotoRunnable, 5000)
        }
    }

    private fun showOverlayBriefly() {
        binding.photoOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, 3500)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    advancePhoto(1)
                    scheduleNextPhoto()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    advancePhoto(-1)
                    scheduleNextPhoto()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    toggleSlideshow()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showOverlayBriefly()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val EXTRA_IMAGE_URLS = "extra_image_urls"
        const val EXTRA_TITLES = "extra_photo_titles"
        const val EXTRA_START_INDEX = "extra_photo_start_index"
    }
}
