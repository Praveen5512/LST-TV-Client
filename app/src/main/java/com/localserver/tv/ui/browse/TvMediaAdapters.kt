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

        // Only load with Coil if item is an actual image. Avoid streaming multi-GB video files through Coil!
        if (item.mediaItem.isImage && item.streamUrl.isNotEmpty()) {
            holder.ivThumbnail.load(item.streamUrl) {
                crossfade(150)
                placeholder(R.drawable.bg_default_cinema)
                error(R.drawable.bg_default_cinema)
                listener(
                    onSuccess = { _, _ -> holder.ivFallback.visibility = View.GONE },
                    onError = { _, _ -> holder.ivFallback.visibility = View.VISIBLE }
                )
            }
        } else {
            holder.ivThumbnail.setImageDrawable(null)
            holder.ivFallback.visibility = View.VISIBLE
        }

        // Calculate progress percentage and remaining time
        if (item.durationMs > 0 && item.resumePositionMs > 0) {
            val progressPercent = ((item.resumePositionMs * 100) / item.durationMs).toInt().coerceIn(0, 100)
            holder.pbWatchProgress.progress = progressPercent
            holder.pbWatchProgress.visibility = View.VISIBLE

            val remainingSec = ((item.durationMs - item.resumePositionMs) / 1000).coerceAtLeast(0)
            val minutes = remainingSec / 60
            val seconds = remainingSec % 60
            holder.tvTimeBadge.text = String.format("%02d:%02d", minutes, seconds)
            holder.tvTimeBadge.visibility = View.VISIBLE
        } else {
            holder.pbWatchProgress.progress = 35
            holder.pbWatchProgress.visibility = View.VISIBLE
            holder.tvTimeBadge.text = "Resume"
            holder.tvTimeBadge.visibility = View.VISIBLE
        }

        // Smooth hardware-accelerated TV Remote focus animation
        holder.container.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            val elevation = if (hasFocus) 12f else 0f
            v.animate()
                .cancel()
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(elevation)
                .setDuration(120)
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

        val media = item.mediaItem
        when {
            media.id == "__BACK__" || item.title.startsWith("⬅") -> {
                holder.ivPoster.setImageDrawable(null)
                holder.ivFallback.visibility = View.VISIBLE
                holder.ivFallback.setImageResource(R.drawable.ic_folder_up)
                holder.tvBadge4k.visibility = View.GONE
                holder.tvBadgeDolby.visibility = View.GONE
            }
            media.isDirectory -> {
                holder.ivPoster.setImageDrawable(null)
                holder.ivFallback.visibility = View.VISIBLE
                holder.ivFallback.setImageResource(R.drawable.ic_folder)
                holder.tvBadge4k.visibility = View.GONE
                holder.tvBadgeDolby.visibility = View.GONE
            }
            media.isAudio -> {
                holder.ivPoster.setImageDrawable(null)
                holder.ivFallback.visibility = View.VISIBLE
                holder.ivFallback.setImageResource(R.drawable.ic_audio)
                holder.tvBadge4k.visibility = View.GONE
                holder.tvBadgeDolby.visibility = View.GONE
            }
            media.isImage -> {
                holder.tvBadge4k.visibility = View.GONE
                holder.tvBadgeDolby.visibility = View.GONE
                if (item.streamUrl.isNotEmpty()) {
                    holder.ivPoster.load(item.streamUrl) {
                        crossfade(150)
                        placeholder(R.drawable.bg_default_cinema)
                        error(R.drawable.bg_default_cinema)
                        listener(
                            onSuccess = { _, _ -> holder.ivFallback.visibility = View.GONE },
                            onError = { _, _ -> holder.ivFallback.visibility = View.VISIBLE }
                        )
                    }
                } else {
                    holder.ivPoster.setImageDrawable(null)
                    holder.ivFallback.visibility = View.VISIBLE
                }
            }
            else -> {
                // Video / Movie
                holder.tvBadge4k.visibility = View.VISIBLE
                holder.tvBadgeDolby.visibility = View.VISIBLE
                holder.ivPoster.setImageDrawable(null)
                holder.ivFallback.visibility = View.VISIBLE
                holder.ivFallback.setImageResource(R.drawable.ic_video)
            }
        }

        // Smooth hardware-accelerated TV Remote focus animation
        holder.container.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            val elevation = if (hasFocus) 12f else 0f
            v.animate()
                .cancel()
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(elevation)
                .setDuration(120)
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
