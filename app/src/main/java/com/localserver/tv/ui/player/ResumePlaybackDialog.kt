package com.localserver.tv.ui.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.localserver.tv.R
import com.localserver.tv.databinding.DialogResumePlaybackBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class ResumePlaybackDialog : DialogFragment() {

    private var _binding: DialogResumePlaybackBinding? = null
    private val binding get() = _binding!!

    private var videoTitle: String = ""
    private var resumePositionMs: Long = 0L
    private var onChoiceSelected: ((resume: Boolean) -> Unit)? = null

    fun setup(title: String, positionMs: Long, onChoice: (resume: Boolean) -> Unit) {
        videoTitle = title
        resumePositionMs = positionMs
        onChoiceSelected = onChoice
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
        _binding = DialogResumePlaybackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvVideoName.text = videoTitle

        val formattedTime = formatMillis(resumePositionMs)
        binding.btnResume.text = getString(R.string.resume_from, formattedTime)

        binding.btnResume.setOnClickListener {
            onChoiceSelected?.invoke(true)
            dismiss()
        }

        binding.btnStartBeginning.setOnClickListener {
            onChoiceSelected?.invoke(false)
            dismiss()
        }

        binding.btnCancelResume.setOnClickListener {
            dismiss()
        }

        // Focused by default on Resume button
        binding.btnResume.requestFocus()
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
        const val TAG = "ResumePlaybackDialog"
        fun newInstance(title: String, positionMs: Long, onChoice: (Boolean) -> Unit): ResumePlaybackDialog {
            val d = ResumePlaybackDialog()
            d.setup(title, positionMs, onChoice)
            return d
        }
    }
}
