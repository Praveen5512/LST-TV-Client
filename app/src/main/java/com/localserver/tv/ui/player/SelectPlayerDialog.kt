package com.localserver.tv.ui.player

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.localserver.tv.R
import com.localserver.tv.data.repository.ServerPreferences
import com.localserver.tv.databinding.DialogSelectPlayerBinding
import kotlinx.coroutines.launch

class SelectPlayerDialog : DialogFragment() {

    private var _binding: DialogSelectPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: ServerPreferences

    private var streamUrl: String = ""
    private var mediaTitle: String = ""
    private var mimeType: String = ""
    private var resumePositionMs: Long = 0L

    private var onPlayInternal: ((startPosition: Long) -> Unit)? = null

    private var isSetAsDefaultChecked: Boolean = false
    private var currentDefaultPkg: String? = null
    private var isExternalDefault: Boolean = false

    data class PlayerEntry(
        val packageName: String,
        val displayName: String,
        val subtitle: String,
        val icon: Drawable?,
        val isBuiltIn: Boolean = false,
        val isChooser: Boolean = false
    )

    fun setup(
        url: String,
        title: String,
        mime: String,
        resumePos: Long,
        onInternalPlay: (Long) -> Unit
    ) {
        streamUrl = url
        mediaTitle = title
        mimeType = mime
        resumePositionMs = resumePos
        onPlayInternal = onInternalPlay
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val displayWidth = resources.displayMetrics.widthPixels
        val targetWidth = (displayWidth * 0.68).toInt().coerceIn(520, 750)
        dialog?.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSelectPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = ServerPreferences(requireContext())

        setupCheckboxToggle()
        setupResetButton()

        binding.btnClosePlayerSelect.setOnClickListener {
            dismiss()
        }

        loadPlayersAndDefaults()
    }

    private fun setupCheckboxToggle() {
        // Toggle the "Set as default" checkbox on click
        isSetAsDefaultChecked = true // Default to true so user gets "set as default" when choosing
        updateCheckboxUI()

        binding.containerSetAsDefault.setOnClickListener {
            isSetAsDefaultChecked = !isSetAsDefaultChecked
            updateCheckboxUI()
        }
    }

    private fun updateCheckboxUI() {
        if (_binding == null) return
        if (isSetAsDefaultChecked) {
            binding.ivCheckbox.setImageResource(R.drawable.ic_check)
            binding.ivCheckbox.setColorFilter(ContextCompat.getColor(requireContext(), R.color.tv_accent))
            binding.tvSetAsDefaultLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.tv_accent))
            binding.tvSetAsDefaultLabel.text = "✓ Always open with selected player (Set as default)"
        } else {
            binding.ivCheckbox.setImageResource(R.drawable.ic_video)
            binding.ivCheckbox.setColorFilter(ContextCompat.getColor(requireContext(), R.color.tv_text_muted))
            binding.tvSetAsDefaultLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.tv_text_secondary))
            binding.tvSetAsDefaultLabel.text = "Open this video once (Do not set as default)"
        }
    }

    private fun setupResetButton() {
        binding.btnResetPlayerDefault.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                preferences.resetDefaultPlayer()
                Toast.makeText(requireContext(), "Default player reset to Built-in Player", Toast.LENGTH_SHORT).show()
                loadPlayersAndDefaults()
            }
        }
    }

    private fun loadPlayersAndDefaults() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            isExternalDefault = preferences.isExternalPlayerDefault()
            currentDefaultPkg = preferences.getDefaultExternalPackage()
            val defaultAppName = preferences.getDefaultExternalAppName()

            // Update top status tag
            if (isExternalDefault) {
                binding.tvCurrentDefaultTag.text = "Default: ${defaultAppName ?: currentDefaultPkg ?: "External"}"
                binding.tvCurrentDefaultTag.visibility = View.VISIBLE
                binding.btnResetPlayerDefault.visibility = View.VISIBLE
            } else {
                binding.tvCurrentDefaultTag.text = "Default: Built-in Player"
                binding.tvCurrentDefaultTag.visibility = View.VISIBLE
                binding.btnResetPlayerDefault.visibility = View.GONE
            }

            // Assemble player options list
            val playerList = mutableListOf<PlayerEntry>()

            // 1. Built-in Player (ExoPlayer)
            playerList.add(
                PlayerEntry(
                    packageName = "builtin",
                    displayName = "Built-in Player (ExoPlayer)",
                    subtitle = "Native hardware-accelerated TV player",
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_video),
                    isBuiltIn = true
                )
            )

            // 2. Discover installed external players (VLC, Nova, MX, Just Player, Kodi, etc.)
            val externalPlayers = ExternalPlayerHelper.getInstalledPlayers(ctx)
            for (app in externalPlayers) {
                playerList.add(
                    PlayerEntry(
                        packageName = app.packageName,
                        displayName = app.appName,
                        subtitle = app.packageName,
                        icon = app.icon
                    )
                )
            }

            // 3. System Chooser fallback
            playerList.add(
                PlayerEntry(
                    packageName = "chooser",
                    displayName = "External App Chooser",
                    subtitle = "Show system app picker every time",
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_open_in_new),
                    isChooser = true
                )
            )

            val adapter = PlayerListAdapter(playerList) { selectedEntry ->
                onPlayerSelected(selectedEntry)
            }

            binding.rvPlayers.layoutManager = LinearLayoutManager(ctx)
            binding.rvPlayers.adapter = adapter
            binding.rvPlayers.requestFocus()
        }
    }

    private fun onPlayerSelected(entry: PlayerEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()

            if (isSetAsDefaultChecked) {
                when {
                    entry.isBuiltIn -> {
                        preferences.resetDefaultPlayer()
                        Toast.makeText(ctx, "Built-in Player set as default", Toast.LENGTH_SHORT).show()
                    }
                    entry.isChooser -> {
                        preferences.setExternalPlayerDefault(true, null, "External Chooser")
                        Toast.makeText(ctx, "External Chooser set as default", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Single selected external player
                        preferences.setExternalPlayerDefault(true, entry.packageName, entry.displayName)
                        Toast.makeText(ctx, "${entry.displayName} set as default player", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Launch playback
            when {
                entry.isBuiltIn -> {
                    onPlayInternal?.invoke(resumePositionMs)
                }
                entry.isChooser -> {
                    ExternalPlayerHelper.openWithExternalApp(ctx, streamUrl, mediaTitle, mimeType)
                }
                else -> {
                    ExternalPlayerHelper.openWithSpecificPlayer(
                        ctx,
                        entry.packageName,
                        streamUrl,
                        mediaTitle,
                        mimeType
                    )
                }
            }

            dismiss()
        }
    }

    inner class PlayerListAdapter(
        private val items: List<PlayerEntry>,
        private val onItemClick: (PlayerEntry) -> Unit
    ) : RecyclerView.Adapter<PlayerListAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val container: View = view.findViewById(R.id.btnPlayerItem)
            val ivIcon: ImageView = view.findViewById(R.id.ivPlayerIcon)
            val tvName: TextView = view.findViewById(R.id.tvPlayerName)
            val tvPackage: TextView = view.findViewById(R.id.tvPlayerPackage)
            val tvBadge: TextView = view.findViewById(R.id.tvDefaultBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_player_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            holder.tvName.text = item.displayName
            holder.tvPackage.text = item.subtitle

            if (item.icon != null) {
                holder.ivIcon.setImageDrawable(item.icon)
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_video)
            }

            // Check if this item is currently the default player
            val isCurrentDefault = when {
                item.isBuiltIn -> !isExternalDefault
                item.isChooser -> isExternalDefault && currentDefaultPkg == null
                else -> isExternalDefault && currentDefaultPkg == item.packageName
            }

            if (isCurrentDefault) {
                holder.tvBadge.text = "ACTIVE DEFAULT"
                holder.tvBadge.visibility = View.VISIBLE
            } else {
                holder.tvBadge.visibility = View.GONE
            }

            holder.container.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SelectPlayerDialog"

        fun newInstance(
            url: String,
            title: String,
            mime: String,
            resumePos: Long,
            onInternalPlay: (Long) -> Unit
        ): SelectPlayerDialog {
            val d = SelectPlayerDialog()
            d.setup(url, title, mime, resumePos, onInternalPlay)
            return d
        }
    }
}
