package com.localserver.tv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.localserver.tv.R
import com.localserver.tv.data.repository.MediaCardModel

/**
 * 10-foot TV List View Adapter for browsing files and folders.
 * Features full filename display with automatic marquee on remote focus (Requirement 4).
 */
class MediaListAdapter(
    private var items: List<MediaCardModel> = emptyList(),
    private val onItemFocused: (MediaCardModel) -> Unit,
    private val onItemClicked: (MediaCardModel, List<MediaCardModel>) -> Unit,
    private val onItemLongClicked: ((MediaCardModel) -> Unit)? = null
) : RecyclerView.Adapter<MediaListAdapter.ViewHolder>() {

    fun submitList(newItems: List<MediaCardModel>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.rowContainer)
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileDetails: TextView = view.findViewById(R.id.tvFileDetails)
        val pbWatchProgress: ProgressBar = view.findViewById(R.id.pbWatchProgress)
        val tvResumeTime: TextView = view.findViewById(R.id.tvResumeTime)
        val tvFormatBadge: TextView = view.findViewById(R.id.tvFormatBadge)
        val ivActionIndicator: ImageView = view.findViewById(R.id.ivActionIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_list_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val media = item.mediaItem

        holder.tvFileName.text = item.title
        holder.tvFileDetails.text = item.subtitle

        val isBackItem = media.id == "__BACK__" || item.title.startsWith("⬅")

        // 1. Icon & Visual Representation
        when {
            isBackItem -> {
                holder.ivThumbnail.visibility = View.GONE
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_folder_up)
                holder.tvFormatBadge.text = "BACK"
            }
            media.isDirectory -> {
                holder.ivThumbnail.visibility = View.GONE
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_folder)
                holder.tvFormatBadge.text = "FOLDER"
            }
            media.isAudio -> {
                holder.ivThumbnail.visibility = View.GONE
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_audio)
                val ext = media.name.substringAfterLast('.', "AUDIO").uppercase()
                holder.tvFormatBadge.text = ext
            }
            media.isImage -> {
                val ext = media.name.substringAfterLast('.', "PHOTO").uppercase()
                holder.tvFormatBadge.text = ext
                if (item.streamUrl.isNotEmpty()) {
                    holder.ivThumbnail.load(item.streamUrl) {
                        crossfade(150)
                        placeholder(R.drawable.ic_photo)
                        error(R.drawable.ic_photo)
                        listener(
                            onSuccess = { _, _ ->
                                holder.ivThumbnail.visibility = View.VISIBLE
                                holder.ivIcon.visibility = View.GONE
                            },
                            onError = { _, _ ->
                                holder.ivThumbnail.visibility = View.GONE
                                holder.ivIcon.visibility = View.VISIBLE
                                holder.ivIcon.setImageResource(R.drawable.ic_photo)
                            }
                        )
                    }
                } else {
                    holder.ivThumbnail.visibility = View.GONE
                    holder.ivIcon.visibility = View.VISIBLE
                    holder.ivIcon.setImageResource(R.drawable.ic_photo)
                }
            }
            else -> {
                // Video file
                holder.ivThumbnail.visibility = View.GONE
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_video)
                val ext = media.name.substringAfterLast('.', "VIDEO").uppercase()
                holder.tvFormatBadge.text = ext
            }
        }

        // 2. Watch Progress Indicator (for videos)
        if (item.durationMs > 0 && item.resumePositionMs > 0) {
            val progressPercent = ((item.resumePositionMs * 100) / item.durationMs).toInt().coerceIn(0, 100)
            holder.pbWatchProgress.progress = progressPercent
            holder.pbWatchProgress.visibility = View.VISIBLE

            val remainingSec = ((item.durationMs - item.resumePositionMs) / 1000).coerceAtLeast(0)
            val minutes = remainingSec / 60
            val seconds = remainingSec % 60
            holder.tvResumeTime.text = String.format("Resume %02d:%02d (%d%%)", minutes, seconds, progressPercent)
            holder.tvResumeTime.visibility = View.VISIBLE
        } else {
            holder.pbWatchProgress.visibility = View.GONE
            holder.tvResumeTime.visibility = View.GONE
        }

        // 3. Smooth D-pad focus animation + Text Marquee (Requirement 4)
        holder.container.setOnFocusChangeListener { v, hasFocus ->
            // Android TextView marquee requires isSelected = true when focused
            holder.tvFileName.isSelected = hasFocus

            val scale = if (hasFocus) 1.015f else 1.0f
            val elevation = if (hasFocus) 8f else 0f
            v.animate()
                .cancel()
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(elevation)
                .setDuration(100)
                .withLayer()
                .start()

            if (hasFocus) {
                onItemFocused(item)
            }
        }

        holder.container.setOnClickListener {
            onItemClicked(item, items)
        }

        holder.container.setOnLongClickListener {
            onItemLongClicked?.invoke(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}

/**
 * Legacy card adapters preserved for backward compatibility
 */
class ContinueWatchingAdapter(
    private var items: List<MediaCardModel> = emptyList(),
    private val onItemFocused: (MediaCardModel) -> Unit,
    private val onItemClicked: (MediaCardModel, List<MediaCardModel>) -> Unit,
    private val onItemLongClicked: ((MediaCardModel) -> Unit)? = null
) : RecyclerView.Adapter<ContinueWatchingAdapter.ViewHolder>() {

    fun submitList(newItems: List<MediaCardModel>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.cardContainer)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val ivFallback: ImageView = view.findViewById(R.id.ivFallbackIcon)
        val tvTimeBadge: TextView = view.findViewById(R.id.tvTimeBadge)
        val pbWatchProgress: ProgressBar = view.findViewById(R.id.pbWatchProgress)
        val tvTitle: TextView = view.findViewById(R.id.tvCardTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvCardSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvSubtitle.text = item.subtitle
        holder.container.setOnClickListener { onItemClicked(item, items) }
    }

    override fun getItemCount(): Int = items.size
}

class MoviePosterAdapter(
    private var items: List<MediaCardModel> = emptyList(),
    private val onItemFocused: (MediaCardModel) -> Unit,
    private val onItemClicked: (MediaCardModel, List<MediaCardModel>) -> Unit,
    private val onItemLongClicked: ((MediaCardModel) -> Unit)? = null
) : RecyclerView.Adapter<MoviePosterAdapter.ViewHolder>() {

    fun submitList(newItems: List<MediaCardModel>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.cardContainer)
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val ivFallback: ImageView = view.findViewById(R.id.ivFallbackIcon)
        val tvBadge4k: TextView = view.findViewById(R.id.tvBadge4k)
        val tvBadgeDolby: TextView = view.findViewById(R.id.tvBadgeDolby)
        val tvTitle: TextView = view.findViewById(R.id.tvCardTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvCardSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movie_poster_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvSubtitle.text = item.subtitle
        holder.container.setOnClickListener { onItemClicked(item, items) }
    }

    override fun getItemCount(): Int = items.size
}
