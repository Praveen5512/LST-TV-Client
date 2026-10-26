package com.localserver.tv.ui.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.localserver.tv.R
import com.localserver.tv.databinding.DialogMediaActionBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class MediaActionDialog : DialogFragment() {

    private var _binding: DialogMediaActionBinding? = null
    private val binding get() = _binding!!

    private var mediaTitle: String = ""
    private var mediaSubtitle: String = ""
    private var streamUrl: String = ""
    private var mimeType: String = ""
    private var resumePositionMs: Long = 0L

    private var onPlayInternal: ((startPosition: Long) -> Unit)? = null

    fun setup(
        title: String,
        subtitle: String,
        url: String,
        mime: String,
        resumePos: Long,
        onInternalPlay: (Long) -> Unit
    ) {
        mediaTitle = title
        mediaSubtitle = subtitle
        streamUrl = url
        mimeType = mime
        resumePositionMs = resumePos
        onPlayInternal = onInternalPlay
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
        _binding = DialogMediaActionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvMediaTitle.text = mediaTitle
        binding.tvMediaSubtitle.text = mediaSubtitle

        // Resume Option if bookmarked
        if (resumePositionMs > 15_000L) {
            val formattedTime = formatMillis(resumePositionMs)
            binding.btnResume.text = getString(R.string.resume_from, formattedTime)
            binding.btnResume.visibility = View.VISIBLE
            binding.btnResume.setOnClickListener {
                onPlayInternal?.invoke(resumePositionMs)
                dismiss()
            }
            binding.btnPlayBuiltin.text = "▶  Start from Beginning"
            binding.btnResume.requestFocus()
        } else {
            binding.btnResume.visibility = View.GONE
            binding.btnPlayBuiltin.text = "▶  Play in Built-in Player"
            binding.btnPlayBuiltin.requestFocus()
        }

        binding.btnPlayBuiltin.setOnClickListener {
            onPlayInternal?.invoke(0L)
            dismiss()
        }

        binding.btnOpenExternal.setOnClickListener {
            ExternalPlayerHelper.openWithExternalApp(requireContext(), streamUrl, mediaTitle, mimeType)
            dismiss()
        }

        binding.btnCancelAction.setOnClickListener {
            dismiss()
        }
    }

    private fun formatMillis(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MediaActionDialog"

        fun newInstance(
            title: String,
            subtitle: String,
            streamUrl: String,
            mimeType: String,
            resumePos: Long,
            onInternalPlay: (Long) -> Unit
        ): MediaActionDialog {
            val d = MediaActionDialog()
            d.setup(title, subtitle, streamUrl, mimeType, resumePos, onInternalPlay)
            return d
        }
    }
}
