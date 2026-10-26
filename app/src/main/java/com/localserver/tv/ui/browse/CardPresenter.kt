package com.localserver.tv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.localserver.tv.R
import com.localserver.tv.data.repository.MediaCardModel

class CardPresenter : Presenter() {

    class ViewHolder(view: View) : Presenter.ViewHolder(view) {
        val container: View = view.findViewById(R.id.cardContainer)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val pbProgress: ProgressBar = view.findViewById(R.id.pbWatchProgress)
        val tvTitle: TextView = view.findViewById(R.id.tvCardTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvCardSubtitle)

        init {
            container.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1.0f
                val elevation = if (hasFocus) 8f else 0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elevation)
                    .setDuration(150)
                    .start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_tv_media_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as ViewHolder
        val card = item as? MediaCardModel ?: return

        holder.tvTitle.text = card.title
        holder.tvSubtitle.text = card.subtitle

        val media = card.mediaItem
        when {
            media.isDirectory -> {
                holder.ivThumbnail.setImageDrawable(null)
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_folder)
                holder.pbProgress.visibility = View.GONE
            }
            media.isImage -> {
                holder.ivIcon.visibility = View.GONE
                holder.ivThumbnail.load(card.streamUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_photo)
                    error(R.drawable.ic_photo)
                }
                holder.pbProgress.visibility = View.GONE
            }
            media.isAudio -> {
                holder.ivThumbnail.setImageDrawable(null)
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_audio)
                holder.pbProgress.visibility = View.GONE
            }
            media.isVideo -> {
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_video)
                // If video streaming endpoint provides image thumbnail or frame preview
                holder.ivThumbnail.setImageDrawable(null)

                if (card.resumePositionMs > 0 && card.durationMs > 0) {
                    val percent = ((card.resumePositionMs * 100) / card.durationMs).toInt().coerceIn(0, 100)
                    holder.pbProgress.progress = percent
                    holder.pbProgress.visibility = View.VISIBLE
                } else {
                    holder.pbProgress.visibility = View.GONE
                }
            }
            else -> {
                holder.ivThumbnail.setImageDrawable(null)
                holder.ivIcon.visibility = View.VISIBLE
                holder.ivIcon.setImageResource(R.drawable.ic_video)
                holder.pbProgress.visibility = View.GONE
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as ViewHolder
        holder.ivThumbnail.setImageDrawable(null)
    }
}
