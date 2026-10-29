package com.localserver.tv.ui.connect

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.localserver.tv.R
import com.localserver.tv.data.api.MediaServerClient
import com.localserver.tv.databinding.DialogManualIpBinding
import kotlinx.coroutines.launch

class ManualIpDialog : DialogFragment() {

    private var _binding: DialogManualIpBinding? = null
    private val binding get() = _binding!!

    private var onServerSelected: ((String) -> Unit)? = null
    private var onConnectionSaved: ((oldUrl: String, newUrl: String) -> Unit)? = null
    private var client: MediaServerClient? = null

    private var editingUrl: String? = null
    private var editingName: String? = null

    private val inputBuilder = StringBuilder()

    fun setListener(listener: (String) -> Unit) {
        onServerSelected = listener
    }

    fun setEditListener(listener: (oldUrl: String, newUrl: String) -> Unit) {
        onConnectionSaved = listener
    }

    fun setClient(mediaServerClient: MediaServerClient) {
        client = mediaServerClient
    }

    fun setEditTarget(existingUrl: String, existingName: String? = null) {
        editingUrl = existingUrl
        editingName = existingName
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val displayWidth = resources.displayMetrics.widthPixels
        val targetWidth = (displayWidth * 0.60).toInt().coerceIn(480, 680)
        dialog?.window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogManualIpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize input if in edit mode
        editingUrl?.let { url ->
            val clean = url.removePrefix("http://").removePrefix("https://").trimEnd('/')
            inputBuilder.setLength(0)
            inputBuilder.append(clean)
            binding.tvTitle.text = getString(R.string.edit_connection_title)
            binding.tvSubtitle.text = getString(R.string.edit_connection_subtitle)
            binding.btnConnect.text = getString(R.string.btn_save)
        } ?: run {
            binding.tvTitle.text = getString(R.string.manual_ip_title)
            binding.tvSubtitle.text = "Enter the IP address shown on your server phone"
            binding.btnConnect.text = getString(R.string.btn_connect)
        }

        setupKeypad()
        updateDisplay()

        // Set initial focus: if in edit mode, focus on Connect/Save or Backspace; if new, focus on prefix
        if (editingUrl != null) {
            binding.btnConnect.requestFocus()
        } else {
            binding.btnQuickPrefix.requestFocus()
        }
    }

    private fun setupKeypad() {
        val digitButtons = mapOf(
            binding.btn0 to "0",
            binding.btn1 to "1",
            binding.btn2 to "2",
            binding.btn3 to "3",
            binding.btn4 to "4",
            binding.btn5 to "5",
            binding.btn6 to "6",
            binding.btn7 to "7",
            binding.btn8 to "8",
            binding.btn9 to "9",
            binding.btnDot to ".",
            binding.btnColon to ":"
        )

        for ((btn, char) in digitButtons) {
            btn.setOnClickListener {
                inputBuilder.append(char)
                updateDisplay()
            }
        }

        binding.btnBackspace.setOnClickListener {
            if (inputBuilder.isNotEmpty()) {
                inputBuilder.deleteCharAt(inputBuilder.length - 1)
                updateDisplay()
            }
        }

        binding.btnClear.setOnClickListener {
            inputBuilder.clear()
            updateDisplay()
        }

        binding.btnQuickPrefix.setOnClickListener {
            if (inputBuilder.isEmpty()) {
                inputBuilder.append("192.168.")
            } else if (!inputBuilder.contains("192.168.")) {
                inputBuilder.insert(0, "192.168.")
            }
            updateDisplay()
        }

        binding.btnQuickPort.setOnClickListener {
            if (!inputBuilder.contains(":")) {
                inputBuilder.append(":8080")
            }
            updateDisplay()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnConnect.setOnClickListener {
            val raw = inputBuilder.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(context, "Please enter an IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val candidateUrl = MediaServerClient.normalizeUrl(raw)
            val isEditMode = editingUrl != null

            binding.btnConnect.isEnabled = false
            binding.btnConnect.text = "Checking..."

            lifecycleScope.launch {
                val isHealthy = client?.checkHealth(candidateUrl) ?: true
                if (isHealthy) {
                    if (isEditMode) {
                        onConnectionSaved?.invoke(editingUrl!!, candidateUrl)
                        Toast.makeText(context, R.string.connection_updated, Toast.LENGTH_SHORT).show()
                    } else {
                        onServerSelected?.invoke(candidateUrl)
                    }
                    dismiss()
                } else {
                    binding.btnConnect.isEnabled = true
                    binding.btnConnect.text = if (isEditMode) getString(R.string.btn_save) else getString(R.string.btn_connect)
                    Toast.makeText(
                        context,
                        "Cannot reach server at $candidateUrl. Check Wi-Fi and port.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateDisplay() {
        val text = inputBuilder.toString()
        if (text.isEmpty()) {
            binding.tvIpDisplay.text = ""
            binding.tvIpDisplay.hint = getString(R.string.manual_ip_hint)
        } else {
            binding.tvIpDisplay.text = text
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ManualIpDialog"

        fun newInstance(): ManualIpDialog = ManualIpDialog()

        fun newEditInstance(existingUrl: String, existingName: String? = null): ManualIpDialog {
            val dialog = ManualIpDialog()
            dialog.setEditTarget(existingUrl, existingName)
            return dialog
        }
    }
}
