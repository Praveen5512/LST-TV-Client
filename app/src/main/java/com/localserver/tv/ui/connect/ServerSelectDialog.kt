package com.localserver.tv.ui.connect

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.localserver.tv.R
import com.localserver.tv.data.api.MediaServerClient
import com.localserver.tv.data.discovery.DiscoveredServer
import com.localserver.tv.data.discovery.TvNsdDiscoveryManager
import com.localserver.tv.data.repository.SavedServerConnection
import com.localserver.tv.data.repository.ServerPreferences
import com.localserver.tv.databinding.DialogServerSelectBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ServerSelectDialog : DialogFragment() {

    private var _binding: DialogServerSelectBinding? = null
    private val binding get() = _binding!!

    private var discoveryManager: TvNsdDiscoveryManager? = null
    private var preferences: ServerPreferences? = null
    private var client: MediaServerClient? = null
    private var onServerSelected: ((String) -> Unit)? = null
    private var onDismissCallback: (() -> Unit)? = null

    private lateinit var adapter: ServerCardAdapter
    private val serverList = mutableListOf<ServerItem>()

    data class ServerItem(
        val name: String,
        val url: String,
        val isDiscovered: Boolean = true,
        val isSaved: Boolean = false,
        val isActive: Boolean = false
    )

    fun setDependencies(
        discovery: TvNsdDiscoveryManager,
        prefs: ServerPreferences,
        mediaClient: MediaServerClient,
        onSelected: (String) -> Unit
    ) {
        discoveryManager = discovery
        preferences = prefs
        client = mediaClient
        onServerSelected = onSelected
    }

    fun setOnDismissListener(callback: () -> Unit) {
        onDismissCallback = callback
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val displayWidth = resources.displayMetrics.widthPixels
        val targetWidth = (displayWidth * 0.68).toInt().coerceIn(540, 760)
        dialog?.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogServerSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ServerCardAdapter(
            items = serverList,
            onConnect = { selectedUrl ->
                onServerSelected?.invoke(selectedUrl)
                dismiss()
            },
            onEdit = { item ->
                showEditDialog(item)
            },
            onDelete = { item ->
                confirmDeleteConnection(item)
            }
        )

        binding.rvServers.layoutManager = LinearLayoutManager(context)
        binding.rvServers.adapter = adapter

        binding.btnManualIp.setOnClickListener {
            val manualDialog = ManualIpDialog.newInstance()
            client?.let { manualDialog.setClient(it) }
            manualDialog.setListener { url ->
                viewLifecycleOwner.lifecycleScope.launch {
                    preferences?.setActiveServer(url)
                    onServerSelected?.invoke(url)
                    dismiss()
                }
            }
            manualDialog.show(parentFragmentManager, ManualIpDialog.TAG)
        }

        binding.btnRescan.setOnClickListener {
            discoveryManager?.rescan()
            binding.tvDiscoveryStatus.text = getString(R.string.server_searching)
        }

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }

        observeServers()
        discoveryManager?.startDiscovery()
    }

    private fun observeServers() {
        viewLifecycleOwner.lifecycleScope.launch {
            discoveryManager?.discoveredServers?.collectLatest { discovered ->
                updateServerList(discovered)
            }
        }
    }

    private suspend fun updateServerList(discovered: List<DiscoveredServer>) {
        val saved: List<SavedServerConnection> = preferences?.getSavedConnections() ?: emptyList()
        val activeUrl = preferences?.getActiveServer()

        serverList.clear()

        // 1. Add active discovered servers
        for (d in discovered) {
            val isAlsoSaved = saved.any { it.url.equals(d.baseUrl, ignoreCase = true) }
            val isActive = activeUrl != null && activeUrl.equals(d.baseUrl, ignoreCase = true)
            serverList.add(
                ServerItem(
                    name = d.name,
                    url = d.baseUrl,
                    isDiscovered = true,
                    isSaved = isAlsoSaved,
                    isActive = isActive
                )
            )
        }

        // 2. Add saved connections not currently in discovered
        for (s in saved) {
            if (serverList.none { it.url.equals(s.url, ignoreCase = true) }) {
                val displayName = s.customName ?: s.url.removePrefix("http://").removePrefix("https://")
                val isActive = activeUrl != null && activeUrl.equals(s.url, ignoreCase = true)
                serverList.add(
                    ServerItem(
                        name = displayName,
                        url = s.url,
                        isDiscovered = false,
                        isSaved = true,
                        isActive = isActive
                    )
                )
            }
        }

        adapter.notifyDataSetChanged()

        if (discovered.isNotEmpty()) {
            binding.tvDiscoveryStatus.text = "Discovered ${discovered.size} media server${if (discovered.size > 1) "s" else ""} on network"
        } else {
            binding.tvDiscoveryStatus.text = getString(R.string.server_searching)
        }

        if (serverList.isEmpty()) {
            binding.tvEmptyMessage.visibility = View.VISIBLE
            binding.rvServers.visibility = View.GONE
            binding.btnManualIp.requestFocus()
        } else {
            binding.tvEmptyMessage.visibility = View.GONE
            binding.rvServers.visibility = View.VISIBLE
            binding.rvServers.requestFocus()
        }
    }

    private fun refreshServerList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentDiscovered = discoveryManager?.discoveredServers?.value ?: emptyList()
            updateServerList(currentDiscovered)
        }
    }

    private fun showEditDialog(item: ServerItem) {
        val editDialog = ManualIpDialog.newEditInstance(item.url, item.name)
        client?.let { editDialog.setClient(it) }
        editDialog.setEditListener { oldUrl, newUrl ->
            viewLifecycleOwner.lifecycleScope.launch {
                preferences?.editServerConnection(oldUrl, newUrl)
                refreshServerList()
            }
        }
        editDialog.show(parentFragmentManager, ManualIpDialog.TAG)
    }

    private fun confirmDeleteConnection(item: ServerItem) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx, R.style.Theme_TvDialog)
            .setTitle(R.string.delete_connection_title)
            .setMessage(getString(R.string.delete_connection_confirm, item.url))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    preferences?.deleteServerConnection(item.url)
                    Toast.makeText(ctx, R.string.connection_deleted, Toast.LENGTH_SHORT).show()
                    refreshServerList()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class ServerCardAdapter(
        private val items: List<ServerItem>,
        private val onConnect: (String) -> Unit,
        private val onEdit: (ServerItem) -> Unit,
        private val onDelete: (ServerItem) -> Unit
    ) : RecyclerView.Adapter<ServerCardAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val btnConnect: LinearLayout = view.findViewById(R.id.btnConnectServer)
            val ivIcon: ImageView = view.findViewById(R.id.ivServerIcon)
            val tvName: TextView = view.findViewById(R.id.tvServerName)
            val tvUrl: TextView = view.findViewById(R.id.tvServerUrl)
            val tvBadge: TextView = view.findViewById(R.id.tvStatusBadge)
            val layoutActions: LinearLayout = view.findViewById(R.id.layoutActions)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEditServer)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteServer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvUrl.text = item.url

            val ctx = holder.itemView.context
            when {
                item.isActive -> {
                    holder.tvBadge.text = ctx.getString(R.string.server_status_active)
                    holder.tvBadge.setTextColor(ctx.getColor(R.color.tv_accent))
                }
                item.isDiscovered -> {
                    holder.tvBadge.text = ctx.getString(R.string.server_status_online)
                    holder.tvBadge.setTextColor(ctx.getColor(R.color.tv_online))
                }
                else -> {
                    holder.tvBadge.text = ctx.getString(R.string.server_status_saved)
                    holder.tvBadge.setTextColor(ctx.getColor(R.color.tv_text_secondary))
                }
            }

            // Click body to connect
            holder.btnConnect.setOnClickListener {
                onConnect(item.url)
            }

            // Long-click body for context menu fallback
            holder.btnConnect.setOnLongClickListener {
                if (item.isSaved) {
                    val options = arrayOf(
                        ctx.getString(R.string.btn_connect),
                        ctx.getString(R.string.btn_edit),
                        ctx.getString(R.string.btn_delete)
                    )
                    AlertDialog.Builder(ctx, R.style.Theme_TvDialog)
                        .setTitle(item.name)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> onConnect(item.url)
                                1 -> onEdit(item)
                                2 -> onDelete(item)
                            }
                        }
                        .show()
                    true
                } else {
                    false
                }
            }

            // Edit button is always available to edit/customize connection
            holder.btnEdit.visibility = View.VISIBLE
            holder.btnEdit.setOnClickListener {
                onEdit(item)
            }

            // Delete button: visible for saved connections
            if (item.isSaved) {
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnDelete.setOnClickListener {
                    onDelete(item)
                }
            } else {
                holder.btnDelete.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val TAG = "ServerSelectDialog"
        fun newInstance(): ServerSelectDialog = ServerSelectDialog()
    }
}
