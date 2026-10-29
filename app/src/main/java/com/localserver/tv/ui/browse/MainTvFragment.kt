package com.localserver.tv.ui.browse

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
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

class MainTvFragment : BrowseSupportFragment() {

    private lateinit var backgroundManager: BackgroundManager
    private var defaultBackground: Drawable? = null
    private lateinit var metrics: DisplayMetrics
    private var backgroundUpdateJob: Job? = null

    private lateinit var client: MediaServerClient
    private lateinit var preferences: ServerPreferences
    private lateinit var repository: MediaRepository
    private lateinit var discoveryManager: TvNsdDiscoveryManager
    private lateinit var actionHandler: MediaItemActionHandler

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var currentServerUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = requireContext()
        client = MediaServerClient()
        preferences = ServerPreferences(ctx)
        repository = MediaRepository(client, preferences)
        discoveryManager = TvNsdDiscoveryManager(ctx)

        actionHandler = MediaItemActionHandler(ctx, parentFragmentManager, preferences) { shareId, folderPath ->
            loadFolderContents(shareId, folderPath)
        }

        setupUIElements()
        prepareBackgroundManager()
        setupEventListeners()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeServerAndLoad()
    }

    private fun setupUIElements() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.tv_bg_default)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.tv_accent)

        // Custom list row presenter with focus handling
        val listRowPresenter = ListRowPresenter().apply {
            shadowEnabled = false
            selectEffectEnabled = false
        }
        rowsAdapter = ArrayObjectAdapter(listRowPresenter)
        adapter = rowsAdapter

        setOnSearchClickedListener {
            showServerSelectDialog()
        }
    }

    private fun prepareBackgroundManager() {
        backgroundManager = BackgroundManager.getInstance(requireActivity())
        backgroundManager.attach(requireActivity().window)
        defaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.tv_banner)
        metrics = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(metrics)
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaCardModel) {
                // If it's a settings action card
                when (item.mediaItem.path) {
                    ACTION_SWITCH_SERVER -> showServerSelectDialog()
                    ACTION_MANUAL_IP -> showManualIpDialog()
                    ACTION_REFRESH -> refreshContent()
                    else -> {
                        // Find siblings in row for playlist/queue context
                        actionHandler.handleItemClicked(item, emptyList())
                    }
                }
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            if (item is MediaCardModel && item.mediaItem.isImage) {
                updateBackgroundDebounced(item.streamUrl)
            } else {
                updateBackgroundDebounced(null)
            }
        }
    }

    private fun updateBackgroundDebounced(imageUrl: String?) {
        backgroundUpdateJob?.cancel()
        backgroundUpdateJob = lifecycleScope.launch {
            delay(300) // 300ms debounce as specified in requirements
            if (imageUrl.isNullOrEmpty()) {
                backgroundManager.drawable = defaultBackground
            } else {
                try {
                    val request = ImageRequest.Builder(requireContext())
                        .data(imageUrl)
                        .size(metrics.widthPixels, metrics.heightPixels)
                        .allowHardware(false)
                        .build()
                    val result = requireContext().imageLoader.execute(request)
                    val drawable = result.drawable
                    if (drawable != null) {
                        backgroundManager.drawable = drawable
                    } else {
                        backgroundManager.drawable = defaultBackground
                    }
                } catch (_: Exception) {
                    backgroundManager.drawable = defaultBackground
                }
            }
        }
    }

    private fun observeServerAndLoad() {
        lifecycleScope.launch {
            discoveryManager.startDiscovery()

            launch {
                discoveryManager.discoveredServers.collectLatest { list ->
                    if (list.isNotEmpty() && currentServerUrl == null) {
                        val first = list.first()
                        val url = first.baseUrl
                        currentServerUrl = url
                        preferences.setActiveServer(url)
                        title = "${getString(R.string.app_name)} • ${first.name}"
                        loadMediaForServer(url)
                    }
                }
            }

            val active = preferences.getActiveServer()
            if (!active.isNullOrBlank()) {
                currentServerUrl = active
                title = "Connecting • $active"
                val result = repository.aggregateBrowseContent(active)
                if (result.isSuccess) {
                    val sections = result.getOrNull().orEmpty()
                    title = "${getString(R.string.app_name)} • $active"
                    populateBrowseRows(sections)
                } else {
                    currentServerUrl = null
                    title = "Server Offline • Searching Wi-Fi / Hotspot…"
                    populateSettingsOnlyRows()
                }
            } else {
                title = "Searching Wi-Fi / Hotspot for Servers…"
                populateSettingsOnlyRows()
            }
        }
    }

    fun loadMediaForServer(baseUrl: String) {
        lifecycleScope.launch {
            currentServerUrl = baseUrl
            val result = repository.aggregateBrowseContent(baseUrl)
            if (result.isSuccess) {
                val sections = result.getOrNull().orEmpty()
                title = "${getString(R.string.app_name)} • $baseUrl"
                populateBrowseRows(sections)
            } else {
                Toast.makeText(
                    context,
                    "Failed to connect to $baseUrl. Searching network…",
                    Toast.LENGTH_LONG
                ).show()
                currentServerUrl = null
                title = "Server Offline • Searching Wi-Fi / Hotspot…"
                populateSettingsOnlyRows()
                discoveryManager.startDiscovery()
            }
        }
    }

    private fun populateBrowseRows(sections: List<CategorySection>) {
        rowsAdapter.clear()
        val cardPresenter = CardPresenter()

        var headerId = 0L
        for (section in sections) {
            val listRowAdapter = ArrayObjectAdapter(cardPresenter)
            for (item in section.items) {
                listRowAdapter.add(item)
            }
            val header = HeaderItem(headerId++, section.title)
            rowsAdapter.add(ListRow(header, listRowAdapter))
        }

        // Add Settings & Switcher Row
        val settingsAdapter = ArrayObjectAdapter(cardPresenter)
        settingsAdapter.add(
            MediaCardModel(
                title = "Switch Server",
                subtitle = "Active: ${currentServerUrl ?: "None"}",
                mediaItem = MediaItemDto(name = "Switch Server", path = ACTION_SWITCH_SERVER, isDirectory = false),
                shareId = "settings",
                shareName = "Settings",
                streamUrl = ""
            )
        )
        settingsAdapter.add(
            MediaCardModel(
                title = "Enter Manual IP",
                subtitle = "Direct connection with PIN pad",
                mediaItem = MediaItemDto(name = "Manual IP", path = ACTION_MANUAL_IP, isDirectory = false),
                shareId = "settings",
                shareName = "Settings",
                streamUrl = ""
            )
        )
        settingsAdapter.add(
            MediaCardModel(
                title = "Refresh Content",
                subtitle = "Rescan active shares",
                mediaItem = MediaItemDto(name = "Refresh", path = ACTION_REFRESH, isDirectory = false),
                shareId = "settings",
                shareName = "Settings",
                streamUrl = ""
            )
        )

        val settingsHeader = HeaderItem(headerId, getString(R.string.category_settings))
        rowsAdapter.add(ListRow(settingsHeader, settingsAdapter))
    }

    private fun populateSettingsOnlyRows() {
        rowsAdapter.clear()
        val cardPresenter = CardPresenter()
        val settingsAdapter = ArrayObjectAdapter(cardPresenter)

        settingsAdapter.add(
            MediaCardModel(
                title = "Select Media Server",
                subtitle = "Discover nearby Wi-Fi servers",
                mediaItem = MediaItemDto(name = "Switch Server", path = ACTION_SWITCH_SERVER, isDirectory = false),
                shareId = "settings",
                shareName = "Settings",
                streamUrl = ""
            )
        )
        settingsAdapter.add(
            MediaCardModel(
                title = "Enter Manual IP",
                subtitle = "Connect using remote keypad",
                mediaItem = MediaItemDto(name = "Manual IP", path = ACTION_MANUAL_IP, isDirectory = false),
                shareId = "settings",
                shareName = "Settings",
                streamUrl = ""
            )
        )

        rowsAdapter.add(ListRow(HeaderItem(0L, "Server Setup Required"), settingsAdapter))
    }

    private fun loadFolderContents(shareId: String, folderPath: String) {
        val server = currentServerUrl ?: return
        lifecycleScope.launch {
            val result = repository.getMedia(server, shareId, folderPath)
            if (result.isSuccess) {
                val list = result.getOrNull()?.items.orEmpty()
                val cardPresenter = CardPresenter()
                val folderRowAdapter = ArrayObjectAdapter(cardPresenter)

                for (item in list) {
                    val streamUrl = item.buildStreamUrl(server, shareId)
                    folderRowAdapter.add(
                        MediaCardModel(
                            title = item.name,
                            subtitle = if (item.isDirectory) "Folder" else item.formattedSize,
                            mediaItem = item,
                            shareId = shareId,
                            shareName = folderPath.ifEmpty { "Root" },
                            streamUrl = streamUrl
                        )
                    )
                }

                val folderHeader = HeaderItem(rowsAdapter.size().toLong(), "Folder: $folderPath")
                rowsAdapter.add(0, ListRow(folderHeader, folderRowAdapter))
                setSelectedPosition(0)
            }
        }
    }

    fun showServerSelectDialog() {
        val dialog = ServerSelectDialog.newInstance()
        dialog.setDependencies(discoveryManager, preferences, client) { selectedUrl ->
            currentServerUrl = selectedUrl
            lifecycleScope.launch {
                preferences.setActiveServer(selectedUrl)
                title = "${getString(R.string.app_name)} • $selectedUrl"
                loadMediaForServer(selectedUrl)
            }
        }
        dialog.setOnDismissListener {
            lifecycleScope.launch {
                val active = preferences.getActiveServer()
                if (active.isNullOrEmpty()) {
                    currentServerUrl = null
                    title = getString(R.string.browse_title)
                    populateSettingsOnlyRows()
                } else if (active != currentServerUrl) {
                    currentServerUrl = active
                    title = "${getString(R.string.app_name)} • $active"
                    loadMediaForServer(active)
                }
            }
        }
        dialog.show(parentFragmentManager, ServerSelectDialog.TAG)
    }

    private fun showManualIpDialog() {
        val manualDialog = ManualIpDialog.newInstance()
        manualDialog.setClient(client)
        manualDialog.setListener { url ->
            currentServerUrl = url
            lifecycleScope.launch {
                preferences.setActiveServer(url)
                title = "${getString(R.string.app_name)} • $url"
                loadMediaForServer(url)
            }
        }
        manualDialog.show(parentFragmentManager, ManualIpDialog.TAG)
    }

    private fun refreshContent() {
        val server = currentServerUrl
        if (!server.isNullOrEmpty()) {
            loadMediaForServer(server)
        } else {
            showServerSelectDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val active = preferences.getActiveServer()
            if (active.isNullOrEmpty()) {
                currentServerUrl = null
                title = getString(R.string.browse_title)
                populateSettingsOnlyRows()
            } else if (active != currentServerUrl) {
                currentServerUrl = active
                title = "${getString(R.string.app_name)} • $active"
                loadMediaForServer(active)
            } else {
                // Reload on resume to update bookmarks
                val result = repository.aggregateBrowseContent(active)
                if (result.isSuccess) {
                    populateBrowseRows(result.getOrNull().orEmpty())
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stopDiscovery()
    }

    companion object {
        private const val ACTION_SWITCH_SERVER = "__ACTION_SWITCH_SERVER__"
        private const val ACTION_MANUAL_IP = "__ACTION_MANUAL_IP__"
        private const val ACTION_REFRESH = "__ACTION_REFRESH__"
    }
}
