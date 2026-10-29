package com.localserver.tv.ui.browse

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.localserver.tv.R
import com.localserver.tv.data.api.MediaItemDto
import com.localserver.tv.data.api.MediaServerClient
import com.localserver.tv.data.discovery.TvNsdDiscoveryManager
import com.localserver.tv.data.repository.MediaCardModel
import com.localserver.tv.data.repository.MediaRepository
import com.localserver.tv.data.repository.ServerPreferences
import com.localserver.tv.ui.connect.ServerSelectDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainTvActivity : FragmentActivity() {

    private lateinit var client: MediaServerClient
    private lateinit var preferences: ServerPreferences
    private lateinit var repository: MediaRepository
    private lateinit var discoveryManager: TvNsdDiscoveryManager
    private lateinit var actionHandler: MediaItemActionHandler

    // Background & Top Bar Views
    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var containerPlayerStatus: LinearLayout
    private lateinit var tvPlayerStatus: TextView
    private lateinit var containerServerStatus: LinearLayout
    private lateinit var ivServerDot: ImageView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvClock: TextView

    // Content State Views
    private lateinit var layoutLoading: LinearLayout
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingSubtitle: TextView

    private lateinit var layoutNoServer: LinearLayout
    private lateinit var tvNoServerTitle: TextView
    private lateinit var btnConnectServer: Button

    private lateinit var layoutContent: LinearLayout
    private lateinit var tvHeaderBreadcrumb: TextView
    private lateinit var tvHeaderCount: TextView
    private lateinit var rvMediaList: RecyclerView
    private lateinit var layoutEmptyList: LinearLayout
    private lateinit var tvEmptyListMessage: TextView

    // List Adapter
    private lateinit var mediaListAdapter: MediaListAdapter

    // Navigation and Data State
    private var currentServerUrl: String? = null
    private var rootFolderCards: List<MediaCardModel> = emptyList()

    data class FolderNavState(
        val shareId: String,
        val shareName: String,
        val folderPath: String,
        val displayBreadcrumb: String
    )

    private val folderNavStack = mutableListOf<FolderNavState>()
    private var backdropJob: Job? = null
    private var loadJob: Job? = null
    private var folderLoadJob: Job? = null

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
        setupMediaListView()
        setupTopBarCapsules()
        setupNoServerView()
        startClock()

        // Requirement 2:
        // When in a folder, back button goes to parent directory.
        // App only exits when reached root directory, showing confirmation prompt:
        // "Are you sure you want to close the app?"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If a folder is currently loading, cancel it and safely return
                if (folderLoadJob?.isActive == true) {
                    folderLoadJob?.cancel()
                    if (folderNavStack.isNotEmpty()) {
                        folderNavStack.removeAt(folderNavStack.lastIndex)
                        if (folderNavStack.isNotEmpty()) {
                            val prev = folderNavStack.last()
                            loadFolderContents(prev.shareId, prev.folderPath, prev.displayBreadcrumb)
                        } else {
                            showContentState()
                            renderRootShares()
                        }
                    } else {
                        showContentState()
                        renderRootShares()
                    }
                    return
                }

                if (folderNavStack.isNotEmpty()) {
                    navigateFolderBack()
                } else {
                    showExitConfirmationDialog()
                }
            }
        })

        // Discovery listener & initial server connection validation
        startDiscoveryListener()
        validateAndConnectActiveServer()
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
        containerPlayerStatus = findViewById(R.id.containerPlayerStatus)
        tvPlayerStatus = findViewById(R.id.tvPlayerStatus)
        containerServerStatus = findViewById(R.id.containerServerStatus)
        ivServerDot = findViewById(R.id.ivServerDot)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvClock = findViewById(R.id.tvClock)

        layoutLoading = findViewById(R.id.layoutLoading)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingSubtitle = findViewById(R.id.tvLoadingSubtitle)

        layoutNoServer = findViewById(R.id.layoutNoServer)
        tvNoServerTitle = findViewById(R.id.tvNoServerTitle)
        btnConnectServer = findViewById(R.id.btnConnectServer)

        layoutContent = findViewById(R.id.layoutContent)
        tvHeaderBreadcrumb = findViewById(R.id.tvHeaderBreadcrumb)
        tvHeaderCount = findViewById(R.id.tvHeaderCount)
        rvMediaList = findViewById(R.id.rvMediaList)
        layoutEmptyList = findViewById(R.id.layoutEmptyList)
        tvEmptyListMessage = findViewById(R.id.tvEmptyListMessage)
    }

    private fun setupMediaListView() {
        mediaListAdapter = MediaListAdapter(
            onItemFocused = { item -> onCardFocused(item) },
            onItemClicked = { item, siblings -> actionHandler.handleItemClicked(item, siblings) },
            onItemLongClicked = { item -> actionHandler.handleItemClicked(item, forceDialog = true) }
        )

        rvMediaList.apply {
            layoutManager = LinearLayoutManager(this@MainTvActivity, LinearLayoutManager.VERTICAL, false)
            adapter = mediaListAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(12)
        }
    }

    private fun setupTopBarCapsules() {
        // Edit / select default external player (Requirement 3)
        containerPlayerStatus.setOnClickListener {
            actionHandler.openSelectPlayerDialog {
                updatePlayerStatus()
            }
        }
        updatePlayerStatus()

        // Switch / select server
        containerServerStatus.setOnClickListener {
            showServerSelectDialog()
        }
    }

    private fun updatePlayerStatus() {
        lifecycleScope.launch {
            val isDefault = preferences.isExternalPlayerDefault()
            val defaultName = preferences.getDefaultExternalAppName()
            val defaultPkg = preferences.getDefaultExternalPackage()

            if (isDefault && (!defaultPkg.isNullOrEmpty() || defaultName != null)) {
                tvPlayerStatus.text = "Player: ${defaultName ?: defaultPkg}"
            } else {
                tvPlayerStatus.text = "Player: Choose"
            }
        }
    }

    private fun setupNoServerView() {
        btnConnectServer.setOnClickListener {
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
            delay(250) // Debounce backdrop load
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

    private fun startDiscoveryListener() {
        discoveryManager.startDiscovery()
        lifecycleScope.launch {
            discoveryManager.discoveredServers.collectLatest { list ->
                if (currentServerUrl == null && list.isNotEmpty()) {
                    val active = preferences.getActiveServer()
                    if (active.isNullOrBlank()) {
                        val first = list.first()
                        validateAndLoadServer(first.baseUrl, first.name)
                    }
                }
            }
        }
    }

    /**
     * Requirement 1 & 3:
     * Validates if active connection is still alive. If not alive, displays "There are no connected server"
     * with button to navigate to connect server options. Shows loading screen until connection data is ready.
     */
    private fun validateAndConnectActiveServer() {
        lifecycleScope.launch {
            val active = preferences.getActiveServer()
            if (active.isNullOrBlank()) {
                showNoServerState()
            } else {
                validateAndLoadServer(active)
            }
        }
    }

    fun validateAndLoadServer(baseUrl: String, customServerName: String? = null) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            showLoadingState(
                title = "Connecting to Server…",
                subtitle = "Checking connection at $baseUrl…"
            )
            updateServerCapsuleText("Connecting to $baseUrl…", isConnecting = true)

            // Step 1: Validate connection is still alive (Requirement 1)
            val isAlive = repository.checkServerHealth(baseUrl)
            if (!isAlive) {
                currentServerUrl = null
                showNoServerState()
                updateServerCapsuleText("Server Offline • No Connection", isConnected = false)
                return@launch
            }

            // Step 2: Fetch and aggregate server media content (Requirement 3: Loading until data ready)
            tvLoadingSubtitle.text = "Loading media shares from server…"
            val result = repository.aggregateBrowseContent(baseUrl)
            if (result.isSuccess) {
                currentServerUrl = baseUrl
                preferences.setActiveServer(baseUrl, customServerName)

                val displayAddress = baseUrl.removePrefix("http://").removePrefix("https://")
                val title = if (!customServerName.isNullOrBlank()) "$customServerName • $displayAddress" else displayAddress
                updateServerCapsuleText("$title (Connected)", isConnected = true)

                val sections = result.getOrNull().orEmpty()
                val folderSection = sections.find { it.id == "folders" }
                rootFolderCards = folderSection?.items.orEmpty()

                folderNavStack.clear()
                showContentState()
                renderRootShares()
            } else {
                currentServerUrl = null
                showNoServerState()
                updateServerCapsuleText("Server Error • Unreachable", isConnected = false)
            }
        }
    }

    private fun showLoadingState(title: String, subtitle: String) {
        layoutLoading.visibility = View.VISIBLE
        tvLoadingTitle.text = title
        tvLoadingSubtitle.text = subtitle

        layoutNoServer.visibility = View.GONE
        layoutContent.visibility = View.GONE
    }

    private fun showNoServerState() {
        layoutLoading.visibility = View.GONE
        layoutContent.visibility = View.GONE

        layoutNoServer.visibility = View.VISIBLE
        tvNoServerTitle.text = "There are no connected server"

        btnConnectServer.requestFocus()
    }

    private fun showContentState() {
        layoutLoading.visibility = View.GONE
        layoutNoServer.visibility = View.GONE
        layoutContent.visibility = View.VISIBLE
    }

    private fun updateServerCapsuleText(text: String, isConnected: Boolean? = null, isConnecting: Boolean = false) {
        tvServerStatus.text = text
        when {
            isConnecting -> {
                ivServerDot.setImageResource(R.drawable.ic_yellow_dot)
            }
            isConnected == true -> {
                ivServerDot.setImageResource(R.drawable.ic_green_dot)
            }
            else -> {
                ivServerDot.setImageResource(R.drawable.ic_red_dot)
            }
        }
    }

    /**
     * Requirement 4:
     * Displays root shared folders without out-of-context text.
     */
    private fun renderRootShares() {
        tvHeaderBreadcrumb.text = "📁 Shared Folders"
        displayMediaList(rootFolderCards, "No shares configured on this server")
    }

    private fun displayMediaList(items: List<MediaCardModel>, emptyMessage: String) {
        tvHeaderCount.text = "${items.size} ${if (items.size == 1) "item" else "items"}"
        mediaListAdapter.submitList(items)

        if (items.isEmpty()) {
            layoutEmptyList.visibility = View.VISIBLE
            tvEmptyListMessage.text = emptyMessage
            rvMediaList.visibility = View.GONE
        } else {
            layoutEmptyList.visibility = View.GONE
            rvMediaList.visibility = View.VISIBLE
            rvMediaList.scrollToPosition(0)
            rvMediaList.requestFocus()
        }
    }

    /**
     * Folder Explorer inside the List View
     */
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

    /**
     * Loads folder contents with a dedicated loading screen and optimized batch data mapping
     * to ensure smooth user experience when opening directories with many items.
     */
    private fun loadFolderContents(shareId: String, folderPath: String, breadcrumb: String) {
        val server = currentServerUrl ?: return

        folderLoadJob?.cancel()
        folderLoadJob = lifecycleScope.launch {
            val folderTitle = if (breadcrumb.contains('/')) {
                breadcrumb.removePrefix("📁 ").substringAfterLast('/').trim()
            } else {
                breadcrumb.removePrefix("📁 ").trim()
            }

            // Show loading screen while directory contents are being fetched and rendered
            showLoadingState(
                title = "Opening $folderTitle…",
                subtitle = "Loading items from server…"
            )

            val result = repository.getMedia(server, shareId, folderPath)
            if (result.isSuccess) {
                val list = result.getOrNull()?.items.orEmpty()

                // Batch load bookmark snapshot once for instantaneous O(1) in-memory lookups
                val bookmarksSnapshot = repository.getAllBookmarksSnapshot()

                // Perform sorting and model construction off the UI thread (Dispatchers.Default)
                val folderCards = withContext(Dispatchers.Default) {
                    val cards = mutableListOf<MediaCardModel>()

                    // Back navigation item
                    val parentName = if (folderNavStack.size > 1) {
                        val prev = folderNavStack[folderNavStack.size - 2]
                        prev.displayBreadcrumb.removePrefix("📁 ").substringAfterLast('/').trim()
                    } else {
                        "Shared Folders"
                    }

                    cards.add(
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
                        val urlHash = streamUrl.hashCode()
                        val bookmark = if (item.isVideo) bookmarksSnapshot["bookmark_$urlHash"] ?: 0L else 0L
                        val duration = if (item.isVideo) bookmarksSnapshot["duration_$urlHash"] ?: 0L else 0L

                        cards.add(
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
                    cards
                }

                // Render content once ready
                showContentState()
                tvHeaderBreadcrumb.text = breadcrumb
                displayMediaList(folderCards, "Empty folder")
            } else {
                showContentState()
                Toast.makeText(
                    this@MainTvActivity,
                    "Failed to open folder: ${result.exceptionOrNull()?.message ?: "Server error"}",
                    Toast.LENGTH_SHORT
                ).show()

                val isAlive = repository.checkServerHealth(server)
                if (!isAlive) {
                    currentServerUrl = null
                    showNoServerState()
                    updateServerCapsuleText("Server Disconnected", isConnected = false)
                    return@launch
                }

                if (folderNavStack.isNotEmpty()) {
                    folderNavStack.removeAt(folderNavStack.lastIndex)
                    if (folderNavStack.isNotEmpty()) {
                        val prev = folderNavStack.last()
                        tvHeaderBreadcrumb.text = prev.displayBreadcrumb
                    } else {
                        tvHeaderBreadcrumb.text = "📁 Shared Folders"
                    }
                }
            }
        }
    }

    /**
     * Requirement 2: Go back to parent directory when hit back button
     */
    private fun navigateFolderBack() {
        if (folderNavStack.isNotEmpty()) {
            folderNavStack.removeAt(folderNavStack.lastIndex)
            if (folderNavStack.isNotEmpty()) {
                val prev = folderNavStack.last()
                loadFolderContents(prev.shareId, prev.folderPath, prev.displayBreadcrumb)
            } else {
                renderRootShares()
            }
        }
    }

    /**
     * Requirement 2:
     * App should only exit when reached root directory.
     * Before exiting, confirmation prompt: "Are you sure you want to close the app?"
     */
    private fun showExitConfirmationDialog() {
        val dialog = ExitConfirmationDialog.newInstance {
            finishAffinity()
        }
        dialog.show(supportFragmentManager, ExitConfirmationDialog.TAG)
    }

    fun showServerSelectDialog() {
        val dialog = ServerSelectDialog.newInstance()
        dialog.setDependencies(discoveryManager, preferences, client) { selectedUrl ->
            validateAndLoadServer(selectedUrl)
        }
        dialog.setOnDismissListener {
            lifecycleScope.launch {
                val active = preferences.getActiveServer()
                if (!active.isNullOrEmpty() && active != currentServerUrl) {
                    validateAndLoadServer(active)
                }
            }
        }
        dialog.show(supportFragmentManager, ServerSelectDialog.TAG)
    }

    override fun onResume() {
        super.onResume()
        updatePlayerStatus()
        lifecycleScope.launch {
            val active = preferences.getActiveServer()
            if (active.isNullOrBlank()) {
                showNoServerState()
            } else {
                val isAlive = repository.checkServerHealth(active)
                if (!isAlive) {
                    currentServerUrl = null
                    showNoServerState()
                    updateServerCapsuleText("Server Offline • No Connection", isConnected = false)
                } else if (currentServerUrl == null) {
                    validateAndLoadServer(active)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        backdropJob?.cancel()
        loadJob?.cancel()
        folderLoadJob?.cancel()
        discoveryManager.stopDiscovery()
    }
}
