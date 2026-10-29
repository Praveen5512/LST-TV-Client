package com.localserver.tv.ui.browse

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.HorizontalGridView
import androidx.lifecycle.lifecycleScope
import coil.load
import com.localserver.tv.R
import com.localserver.tv.data.api.MediaItemDto
import com.localserver.tv.data.api.MediaServerClient
import com.localserver.tv.data.discovery.TvNsdDiscoveryManager
import com.localserver.tv.data.repository.CategorySection
import com.localserver.tv.data.repository.MediaCardModel
import com.localserver.tv.data.repository.MediaRepository
import com.localserver.tv.data.repository.ServerPreferences
import com.localserver.tv.ui.connect.ManualIpDialog
import com.localserver.tv.ui.connect.ServerSelectDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainTvActivity : FragmentActivity() {

    private lateinit var client: MediaServerClient
    private lateinit var preferences: ServerPreferences
    private lateinit var repository: MediaRepository
    private lateinit var discoveryManager: TvNsdDiscoveryManager
    private lateinit var actionHandler: MediaItemActionHandler

    // UI View References
    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var tvServerStatus: TextView
    private lateinit var containerServerStatus: LinearLayout
    private lateinit var tvClock: TextView
    private lateinit var scrollRows: NestedScrollView

    // Left Nav Rail
    private lateinit var btnNavHome: FrameLayout
    private lateinit var btnNavContinue: FrameLayout
    private lateinit var btnNavMovies: FrameLayout
    private lateinit var btnNavMusic: FrameLayout
    private lateinit var btnNavFolders: FrameLayout
    private lateinit var btnNavSettings: FrameLayout

    // Content Sections & Grids
    private lateinit var layoutContinueWatching: LinearLayout
    private lateinit var hgvContinueWatching: HorizontalGridView
    private lateinit var layoutMovies: LinearLayout
    private lateinit var hgvMovies: HorizontalGridView
    private lateinit var layoutMusic: LinearLayout
    private lateinit var hgvMusic: HorizontalGridView
    private lateinit var layoutFolders: LinearLayout
    private lateinit var hgvFolders: HorizontalGridView
    private lateinit var tvHeaderFolders: TextView

    data class FolderNavState(
        val shareId: String,
        val shareName: String,
        val folderPath: String,
        val displayBreadcrumb: String
    )

    private val folderNavStack = mutableListOf<FolderNavState>()
    private var rootFolderCards: List<MediaCardModel> = emptyList()

    // Adapters
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private lateinit var moviesAdapter: MoviePosterAdapter
    private lateinit var musicAdapter: MoviePosterAdapter
    private lateinit var foldersAdapter: MoviePosterAdapter

    private var currentServerUrl: String? = null
    private var backdropJob: Job? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initDependencies()
        bindViews()
        setupNavRail()
        setupGrids()
        setupServerCapsule()
        startClock()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (folderNavStack.isNotEmpty()) {
                    navigateFolderBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        observeServerAndLoad()
    }

    private fun initDependencies() {
        client = MediaServerClient()
        preferences = ServerPreferences(this)
        repository = MediaRepository(client, preferences)
        discoveryManager = TvNsdDiscoveryManager(this)

        actionHandler = MediaItemActionHandler(
            context = this,
            fragmentManager = supportFragmentManager,
            preferences = preferences,
            onFolderBackSelected = {
                navigateFolderBack()
            },
            onFolderSelected = { shareId, folderPath, folderTitle ->
                openFolder(shareId, folderPath, folderTitle)
            }
        )
    }

    private fun bindViews() {
        ivHeroBackdrop = findViewById(R.id.ivHeroBackdrop)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        containerServerStatus = findViewById(R.id.containerServerStatus)
        tvClock = findViewById(R.id.tvClock)
        scrollRows = findViewById(R.id.scrollRows)

        btnNavHome = findViewById(R.id.btnNavHome)
        btnNavContinue = findViewById(R.id.btnNavContinue)
        btnNavMovies = findViewById(R.id.btnNavMovies)
        btnNavMusic = findViewById(R.id.btnNavMusic)
        btnNavFolders = findViewById(R.id.btnNavFolders)
        btnNavSettings = findViewById(R.id.btnNavSettings)

        layoutContinueWatching = findViewById(R.id.layoutContinueWatching)
        hgvContinueWatching = findViewById(R.id.hgvContinueWatching)
        layoutMovies = findViewById(R.id.layoutMovies)
        hgvMovies = findViewById(R.id.hgvMovies)
        layoutMusic = findViewById(R.id.layoutMusic)
        hgvMusic = findViewById(R.id.hgvMusic)
        layoutFolders = findViewById(R.id.layoutFolders)
        hgvFolders = findViewById(R.id.hgvFolders)
        tvHeaderFolders = findViewById(R.id.tvHeaderFolders)
    }

    private fun setupNavRail() {
        btnNavHome.isSelected = true

        val navButtons = listOf(btnNavHome, btnNavContinue, btnNavMovies, btnNavMusic, btnNavFolders, btnNavSettings)

        fun selectNavTab(selected: FrameLayout) {
            navButtons.forEach { it.isSelected = (it == selected) }
        }

        btnNavHome.setOnClickListener {
            selectNavTab(btnNavHome)
            scrollRows.smoothScrollTo(0, 0)
            if (layoutFolders.visibility == View.VISIBLE && hgvFolders.adapter?.itemCount ?: 0 > 0) {
                hgvFolders.requestFocus()
            } else if (hgvContinueWatching.adapter?.itemCount ?: 0 > 0) {
                hgvContinueWatching.requestFocus()
            } else {
                hgvMovies.requestFocus()
            }
        }

        btnNavContinue.setOnClickListener {
            selectNavTab(btnNavContinue)
            scrollRows.smoothScrollTo(0, layoutContinueWatching.top)
            hgvContinueWatching.requestFocus()
        }

        btnNavMovies.setOnClickListener {
            selectNavTab(btnNavMovies)
            scrollRows.smoothScrollTo(0, layoutMovies.top)
            hgvMovies.requestFocus()
        }

        btnNavMusic.setOnClickListener {
            selectNavTab(btnNavMusic)
            if (layoutMusic.visibility == View.VISIBLE) {
                scrollRows.smoothScrollTo(0, layoutMusic.top)
                hgvMusic.requestFocus()
            }
        }

        btnNavFolders.setOnClickListener {
            selectNavTab(btnNavFolders)
            if (layoutFolders.visibility == View.VISIBLE) {
                scrollRows.smoothScrollTo(0, layoutFolders.top)
                hgvFolders.requestFocus()
            }
        }

        btnNavSettings.setOnClickListener {
            showServerSelectDialog()
        }
    }

    private fun setupGrids() {
        val sharedPool = androidx.recyclerview.widget.RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        }

        continueWatchingAdapter = ContinueWatchingAdapter(
            onItemFocused = { item -> onCardFocused(item) },
            onItemClicked = { item, siblings -> actionHandler.handleItemClicked(item, siblings) },
            onItemLongClicked = { item -> actionHandler.handleItemClicked(item, forceDialog = true) }
        )
        hgvContinueWatching.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(6)
            isNestedScrollingEnabled = false
            adapter = continueWatchingAdapter
        }

        moviesAdapter = MoviePosterAdapter(
            onItemFocused = { item -> onCardFocused(item) },
            onItemClicked = { item, siblings -> actionHandler.handleItemClicked(item, siblings) },
            onItemLongClicked = { item -> actionHandler.handleItemClicked(item, forceDialog = true) }
        )
        hgvMovies.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            setRecycledViewPool(sharedPool)
            isNestedScrollingEnabled = false
            adapter = moviesAdapter
        }

        musicAdapter = MoviePosterAdapter(
            onItemFocused = { item -> onCardFocused(item) },
            onItemClicked = { item, siblings -> actionHandler.handleItemClicked(item, siblings) },
            onItemLongClicked = { item -> actionHandler.handleItemClicked(item, forceDialog = true) }
        )
        hgvMusic.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            setRecycledViewPool(sharedPool)
            isNestedScrollingEnabled = false
            adapter = musicAdapter
        }

        foldersAdapter = MoviePosterAdapter(
            onItemFocused = { item -> onCardFocused(item) },
            onItemClicked = { item, siblings -> actionHandler.handleItemClicked(item, siblings) },
            onItemLongClicked = { item -> actionHandler.handleItemClicked(item, forceDialog = true) }
        )
        hgvFolders.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            setRecycledViewPool(sharedPool)
            isNestedScrollingEnabled = false
            adapter = foldersAdapter
        }
    }

    private fun setupServerCapsule() {
        containerServerStatus.setOnClickListener {
            showServerSelectDialog()
        }
    }

    private fun startClock() {
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30000)
    }

    private fun updateClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvClock.text = sdf.format(Date())
    }

    private fun onCardFocused(item: MediaCardModel) {
        backdropJob?.cancel()
        backdropJob = lifecycleScope.launch {
            delay(300) // Debounce backdrop load
            // Only load backdrop via Coil if item is an actual image to avoid streaming video data
            if (item.mediaItem.isImage && item.streamUrl.isNotEmpty()) {
                ivHeroBackdrop.load(item.streamUrl) {
                    crossfade(150)
                    placeholder(R.drawable.bg_default_cinema)
                    error(R.drawable.bg_default_cinema)
                }
            } else {
                ivHeroBackdrop.setImageResource(R.drawable.bg_default_cinema)
            }
        }
    }

    private fun observeServerAndLoad() {
        lifecycleScope.launch {
            // Continuously scan for available media servers on Wi-Fi and Hotspot
            discoveryManager.startDiscovery()

            launch {
                discoveryManager.discoveredServers.collectLatest { list ->
                    if (list.isNotEmpty()) {
                        // Auto-connect if no server is active or if previous server connection failed
                        if (currentServerUrl == null) {
                            val first = list.first()
                            val url = first.baseUrl
                            currentServerUrl = url
                            preferences.setActiveServer(url)
                            updateServerCapsuleText("${first.name} • ${first.host}:${first.port} (Connected)")
                            loadMediaForServer(url)
                        }
                    }
                }
            }

            val active = preferences.getActiveServer()
            if (!active.isNullOrBlank()) {
                currentServerUrl = active
                updateServerCapsuleText("Connecting to $active…")
                val result = repository.aggregateBrowseContent(active)
                if (result.isSuccess) {
                    val sections = result.getOrNull().orEmpty()
                    updateServerCapsuleText(active)
                    populateBrowseRows(sections)
                } else {
                    currentServerUrl = null
                    updateServerCapsuleText("Server offline • Searching Wi-Fi / Hotspot…")
                    loadDemoPreviewContent()
                    // Discovery is active and will auto-connect once server is found
                }
            } else {
                updateServerCapsuleText("Searching for Servers on Wi-Fi / Hotspot…")
                loadDemoPreviewContent()
            }
        }
    }

    private fun updateServerCapsuleText(text: String) {
        tvServerStatus.text = text
    }

    fun loadMediaForServer(baseUrl: String) {
        lifecycleScope.launch {
            currentServerUrl = baseUrl
            val result = repository.aggregateBrowseContent(baseUrl)
            if (result.isSuccess) {
                val sections = result.getOrNull().orEmpty()
                updateServerCapsuleText(baseUrl)
                populateBrowseRows(sections)
            } else {
                Toast.makeText(
                    this@MainTvActivity,
                    "Failed to connect to $baseUrl. Searching network…",
                    Toast.LENGTH_SHORT
                ).show()
                currentServerUrl = null
                updateServerCapsuleText("Server offline • Searching Wi-Fi / Hotspot…")
                loadDemoPreviewContent()
                discoveryManager.startDiscovery()
            }
        }
    }

    private fun populateBrowseRows(sections: List<CategorySection>) {
        val recentSection = sections.find { it.id == "recent" }
        if (recentSection != null && recentSection.items.isNotEmpty()) {
            layoutContinueWatching.visibility = View.VISIBLE
            continueWatchingAdapter.submitList(recentSection.items)
        } else {
            layoutContinueWatching.visibility = View.GONE
        }

        val videoSection = sections.find { it.id == "videos" }
        if (videoSection != null && videoSection.items.isNotEmpty()) {
            layoutMovies.visibility = View.VISIBLE
            moviesAdapter.submitList(videoSection.items)
        } else {
            layoutMovies.visibility = View.GONE
        }

        val musicSection = sections.find { it.id == "audio" }
        if (musicSection != null && musicSection.items.isNotEmpty()) {
            layoutMusic.visibility = View.VISIBLE
            musicAdapter.submitList(musicSection.items)
        } else {
            layoutMusic.visibility = View.GONE
        }

        val folderSection = sections.find { it.id == "folders" }
        if (folderSection != null && folderSection.items.isNotEmpty()) {
            rootFolderCards = folderSection.items
            layoutFolders.visibility = View.VISIBLE
            if (folderNavStack.isEmpty()) {
                tvHeaderFolders.text = "Folders & Shares Explorer"
                foldersAdapter.submitList(rootFolderCards)
            }
        } else {
            if (folderNavStack.isEmpty()) {
                layoutFolders.visibility = View.GONE
            }
        }

        // If no items at all, show demo
        if (sections.isEmpty() || sections.all { it.items.isEmpty() }) {
            loadDemoPreviewContent()
        }
    }

    private fun loadDemoPreviewContent() {
        val demoContinueWatching = listOf(
            MediaCardModel(
                title = "Interstellar",
                subtitle = "14.2 GB • 1080p",
                mediaItem = MediaItemDto(name = "Interstellar.mkv", path = "Movies/Interstellar.mkv", isDirectory = false, size = 15247187968L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = "",
                resumePositionMs = 2540000L,
                durationMs = 10140000L
            ),
            MediaCardModel(
                title = "Blade Runner 2049",
                subtitle = "18.5 GB • 4K UHD",
                mediaItem = MediaItemDto(name = "BladeRunner2049.mkv", path = "Movies/BladeRunner2049.mkv", isDirectory = false, size = 19864223744L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = "",
                resumePositionMs = 6420000L,
                durationMs = 9840000L
            ),
            MediaCardModel(
                title = "Dune: Part Two",
                subtitle = "22.1 GB • 4K Dolby",
                mediaItem = MediaItemDto(name = "Dune2.2024.mkv", path = "Movies/Dune2.2024.mkv", isDirectory = false, size = 23728867328L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = "",
                resumePositionMs = 3300000L,
                durationMs = 9960000L
            ),
            MediaCardModel(
                title = "Oppenheimer",
                subtitle = "24.6 GB • 4K IMAX",
                mediaItem = MediaItemDto(name = "Oppenheimer.mkv", path = "Movies/Oppenheimer.mkv", isDirectory = false, size = 26414776320L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = "",
                resumePositionMs = 180000L,
                durationMs = 10800000L
            )
        )

        val demoMovies = listOf(
            MediaCardModel(
                title = "Interstellar",
                subtitle = "14.2 GB • 1080p • 2h 49m",
                mediaItem = MediaItemDto(name = "Interstellar.mkv", path = "Movies/Interstellar.mkv", isDirectory = false, size = 15247187968L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = ""
            ),
            MediaCardModel(
                title = "Arrival",
                subtitle = "8.4 GB • 1080p • 1h 56m",
                mediaItem = MediaItemDto(name = "Arrival.2016.mkv", path = "Movies/Arrival.2016.mkv", isDirectory = false, size = 9019431321L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = ""
            ),
            MediaCardModel(
                title = "The Dark Knight",
                subtitle = "12.8 GB • 4K • 2h 32m",
                mediaItem = MediaItemDto(name = "DarkKnight.mkv", path = "Movies/DarkKnight.mkv", isDirectory = false, size = 13743895347L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = ""
            ),
            MediaCardModel(
                title = "Inception",
                subtitle = "11.2 GB • 1080p • 2h 28m",
                mediaItem = MediaItemDto(name = "Inception.mkv", path = "Movies/Inception.mkv", isDirectory = false, size = 12025908428L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = ""
            ),
            MediaCardModel(
                title = "The Matrix",
                subtitle = "9.6 GB • 4K • 2h 16m",
                mediaItem = MediaItemDto(name = "Matrix.mkv", path = "Movies/Matrix.mkv", isDirectory = false, size = 10307921510L, mimeType = "video/x-matroska"),
                shareId = "demo_movies",
                shareName = "Movies",
                streamUrl = ""
            )
        )

        val demoFolders = listOf(
            MediaCardModel(
                title = "Movies & Cinema",
                subtitle = "Share Folder",
                mediaItem = MediaItemDto(name = "Movies & Cinema", path = "", isDirectory = true),
                shareId = "demo_movies",
                shareName = "Movies & Cinema",
                streamUrl = ""
            ),
            MediaCardModel(
                title = "TV Series & Anime",
                subtitle = "Share Folder",
                mediaItem = MediaItemDto(name = "TV Series & Anime", path = "", isDirectory = true),
                shareId = "demo_series",
                shareName = "TV Series & Anime",
                streamUrl = ""
            ),
            MediaCardModel(
                title = "Music Library",
                subtitle = "Share Folder",
                mediaItem = MediaItemDto(name = "Music Library", path = "", isDirectory = true),
                shareId = "demo_music",
                shareName = "Music Library",
                streamUrl = ""
            )
        )

        rootFolderCards = demoFolders
        layoutFolders.visibility = View.VISIBLE
        tvHeaderFolders.text = "Folders & Shares Explorer"
        foldersAdapter.submitList(demoFolders)

        layoutContinueWatching.visibility = View.VISIBLE
        continueWatchingAdapter.submitList(demoContinueWatching)

        layoutMovies.visibility = View.VISIBLE
        moviesAdapter.submitList(demoMovies)
    }

    private fun openFolder(shareId: String, folderPath: String, folderTitle: String) {
        val shareName = if (folderNavStack.isEmpty()) {
            folderTitle
        } else {
            folderNavStack.first().shareName
        }
        val breadcrumb = if (folderNavStack.isEmpty()) {
            "📁 $folderTitle"
        } else {
            val cleanPath = folderPath.trim('/')
            "📁 $shareName / ${cleanPath.substringAfterLast('/')}"
        }
        folderNavStack.add(
            FolderNavState(
                shareId = shareId,
                shareName = shareName,
                folderPath = folderPath,
                displayBreadcrumb = breadcrumb
            )
        )
        loadFolderContents(shareId, folderPath, breadcrumb)
    }

    private fun loadFolderContents(shareId: String, folderPath: String, breadcrumb: String) {
        val server = currentServerUrl ?: return
        lifecycleScope.launch {
            tvHeaderFolders.text = breadcrumb
            val result = repository.getMedia(server, shareId, folderPath)
            if (result.isSuccess) {
                val list = result.getOrNull()?.items.orEmpty()
                val folderCards = mutableListOf<MediaCardModel>()

                // Prepend Back Card
                val parentName = if (folderNavStack.size > 1) {
                    val prev = folderNavStack[folderNavStack.size - 2]
                    prev.displayBreadcrumb.removePrefix("📁 ").substringAfterLast('/')
                } else {
                    "Shares List"
                }

                folderCards.add(
                    MediaCardModel(
                        title = "⬅ .. [Back]",
                        subtitle = "Back to $parentName",
                        mediaItem = MediaItemDto(
                            id = "__BACK__",
                            name = "..",
                            path = "__BACK__",
                            isDirectory = true
                        ),
                        shareId = shareId,
                        shareName = parentName,
                        streamUrl = ""
                    )
                )

                // Sort: Subfolders first, then files alphabetically
                val sorted = list.sortedWith(
                    compareByDescending<MediaItemDto> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )

                for (item in sorted) {
                    val streamUrl = item.buildStreamUrl(server, shareId)
                    val bookmark = if (item.isVideo) preferences.getPlaybackBookmark(streamUrl) else 0L
                    val duration = if (item.isVideo) preferences.getMediaDuration(streamUrl) else 0L

                    folderCards.add(
                        MediaCardModel(
                            title = item.name,
                            subtitle = when {
                                item.isDirectory -> "Folder"
                                item.isVideo -> "${item.formattedSize} • Video"
                                item.isAudio -> "${item.formattedSize} • Audio"
                                item.isImage -> "${item.formattedSize} • Photo"
                                else -> item.formattedSize.ifEmpty { "File" }
                            },
                            mediaItem = item,
                            shareId = shareId,
                            shareName = breadcrumb.removePrefix("📁 "),
                            streamUrl = streamUrl,
                            resumePositionMs = bookmark,
                            durationMs = duration
                        )
                    )
                }

                layoutFolders.visibility = View.VISIBLE
                foldersAdapter.submitList(folderCards)
                scrollRows.smoothScrollTo(0, layoutFolders.top)
                hgvFolders.requestFocus()

                if (list.isEmpty()) {
                    Toast.makeText(this@MainTvActivity, "Empty folder", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(
                    this@MainTvActivity,
                    "Failed to open folder: ${result.exceptionOrNull()?.message ?: "Server error"}",
                    Toast.LENGTH_SHORT
                ).show()
                if (folderNavStack.isNotEmpty()) {
                    folderNavStack.removeAt(folderNavStack.lastIndex)
                    if (folderNavStack.isNotEmpty()) {
                        val prev = folderNavStack.last()
                        tvHeaderFolders.text = prev.displayBreadcrumb
                    } else {
                        tvHeaderFolders.text = "Folders & Shares Explorer"
                    }
                }
            }
        }
    }

    private fun navigateFolderBack() {
        if (folderNavStack.isNotEmpty()) {
            folderNavStack.removeAt(folderNavStack.lastIndex)
            if (folderNavStack.isNotEmpty()) {
                val prev = folderNavStack.last()
                loadFolderContents(prev.shareId, prev.folderPath, prev.displayBreadcrumb)
            } else {
                // Returned to Root Shares Explorer
                tvHeaderFolders.text = "Folders & Shares Explorer"
                foldersAdapter.submitList(rootFolderCards)
                scrollRows.smoothScrollTo(0, layoutFolders.top)
                hgvFolders.requestFocus()
            }
        }
    }

    fun showServerSelectDialog() {
        val dialog = ServerSelectDialog.newInstance()
        dialog.setDependencies(discoveryManager, preferences, client) { selectedUrl ->
            currentServerUrl = selectedUrl
            lifecycleScope.launch {
                preferences.setActiveServer(selectedUrl)
                updateServerCapsuleText(selectedUrl)
                loadMediaForServer(selectedUrl)
            }
        }
        dialog.setOnDismissListener {
            lifecycleScope.launch {
                val active = preferences.getActiveServer()
                if (!active.isNullOrEmpty() && active != currentServerUrl) {
                    currentServerUrl = active
                    updateServerCapsuleText(active)
                    loadMediaForServer(active)
                }
            }
        }
        dialog.show(supportFragmentManager, ServerSelectDialog.TAG)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val active = preferences.getActiveServer()
            if (!active.isNullOrEmpty()) {
                currentServerUrl = active
                updateServerCapsuleText(active)
                loadMediaForServer(active)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        backdropJob?.cancel()
        discoveryManager.stopDiscovery()
    }
}
