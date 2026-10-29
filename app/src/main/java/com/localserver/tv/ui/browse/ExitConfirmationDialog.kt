package com.localserver.tv.ui.browse

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import com.localserver.tv.R

class ExitConfirmationDialog : DialogFragment() {

    private var onExitConfirmed: (() -> Unit)? = null

    fun setOnExitConfirmed(listener: () -> Unit) {
        onExitConfirmed = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val displayWidth = resources.displayMetrics.widthPixels
        val targetWidth = (displayWidth * 0.48).toInt().coerceIn(400, 560)
        dialog?.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_exit_confirmation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnCancel = view.findViewById<Button>(R.id.btnCancelExit)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmExit)

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnConfirm.setOnClickListener {
            dismiss()
            onExitConfirmed?.invoke()
        }

        // Cancel button is focused by default to prevent accidental exit
        btnCancel.requestFocus()
    }

    companion object {
        const val TAG = "ExitConfirmationDialog"

        fun newInstance(onExit: () -> Unit): ExitConfirmationDialog {
            val dialog = ExitConfirmationDialog()
            dialog.setOnExitConfirmed(onExit)
            return dialog
        }
    }
}
