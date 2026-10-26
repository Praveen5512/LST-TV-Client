package com.localserver.tv.ui.connect

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.localserver.tv.R
import com.localserver.tv.data.api.MediaServerClient
import com.localserver.tv.data.discovery.DiscoveredServer
import com.localserver.tv.data.discovery.TvNsdDiscoveryManager
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

    private lateinit var adapter: ServerCardAdapter
    private val serverList = mutableListOf<ServerItem>()

    data class ServerItem(
        val name: String,
        val url: String,
        val isDiscovered: Boolean = true
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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
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

        adapter = ServerCardAdapter(serverList) { selectedUrl ->
            onServerSelected?.invoke(selectedUrl)
            dismiss()
        }

        binding.rvServers.layoutManager = LinearLayoutManager(context)
        binding.rvServers.adapter = adapter

        binding.btnManualIp.setOnClickListener {
            val manualDialog = ManualIpDialog.newInstance()
            client?.let { manualDialog.setClient(it) }
            manualDialog.setListener { url ->
                onServerSelected?.invoke(url)
                dismiss()
            }
            manualDialog.show(parentFragmentManager, ManualIpDialog.TAG)
        }

        binding.btnRescan.setOnClickListener {
            discoveryManager?.stopDiscovery()
            discoveryManager?.startDiscovery()
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
                val recent: List<String> = preferences?.getRecentServers() ?: emptyList()

                serverList.clear()

                // Add active discovered servers first
                for (d in discovered) {
                    serverList.add(ServerItem(name = d.name, url = d.baseUrl, isDiscovered = true))
                }

                // Add previous recents if not already in list
                for (rec in recent) {
                    if (serverList.none { it.url.equals(rec, ignoreCase = true) }) {
                        serverList.add(ServerItem(name = "Recent: $rec", url = rec, isDiscovered = false))
                    }
                }

                adapter.notifyDataSetChanged()

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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class ServerCardAdapter(
        private val items: List<ServerItem>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<ServerCardAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvServerName)
            val tvUrl: TextView = view.findViewById(R.id.tvServerUrl)
            val tvBadge: TextView = view.findViewById(R.id.tvStatusBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvUrl.text = item.url
            if (item.isDiscovered) {
                holder.tvBadge.text = "Discovered"
                holder.tvBadge.setTextColor(holder.itemView.context.getColor(R.color.tv_online))
            } else {
                holder.tvBadge.text = "Recent"
                holder.tvBadge.setTextColor(holder.itemView.context.getColor(R.color.tv_text_secondary))
            }

            holder.itemView.setOnClickListener {
                onClick(item.url)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val TAG = "ServerSelectDialog"
        fun newInstance(): ServerSelectDialog = ServerSelectDialog()
    }
}
