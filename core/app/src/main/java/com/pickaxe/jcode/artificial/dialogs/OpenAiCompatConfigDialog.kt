package com.pickaxe.jcode.artificial.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.pickaxe.jcode.R
import com.pickaxe.jcode.app.BaseApplication

class OpenAiCompatConfigDialog(
    private val onSave: (name: String, baseUrl: String, apiKey: String, modelName: String) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var nameInput: TextInputEditText
    private lateinit var baseUrlInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var modelNameInput: TextInputEditText
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_openai_compat_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        nameInput = view.findViewById(R.id.nameInput)
        baseUrlInput = view.findViewById(R.id.baseUrlInput)
        apiKeyInput = view.findViewById(R.id.apiKeyInput)
        modelNameInput = view.findViewById(R.id.modelNameInput)
        saveButton = view.findViewById(R.id.saveButton)
        cancelButton = view.findViewById(R.id.cancelButton)

        loadSavedConfig()

        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val baseUrl = baseUrlInput.text.toString().trim()
            val apiKey = apiKeyInput.text.toString().trim()
            val modelName = modelNameInput.text.toString().trim()

            if (baseUrl.isNotEmpty() && modelName.isNotEmpty()) {
                saveConfig(name, baseUrl, apiKey, modelName)
                onSave(name, baseUrl, apiKey, modelName)
                dismiss()
            }
        }

        cancelButton.setOnClickListener {
            dismiss()
        }
    }

    private fun loadSavedConfig() {
        val prefs = BaseApplication.getBaseInstance().prefManager
        nameInput.setText(prefs.getString("openai_compat_name", "OpenAI 兼容 API"))
        baseUrlInput.setText(prefs.getString("openai_compat_base_url", ""))
        apiKeyInput.setText(prefs.getString("openai_compat_api_key", ""))
        modelNameInput.setText(prefs.getString("openai_compat_model", ""))
    }

    private fun saveConfig(name: String, baseUrl: String, apiKey: String, modelName: String) {
        val prefs = BaseApplication.getBaseInstance().prefManager
        prefs.putString("openai_compat_name", name.ifBlank { "OpenAI 兼容 API" })
        prefs.putString("openai_compat_base_url", baseUrl)
        prefs.putString("openai_compat_api_key", apiKey)
        prefs.putString("openai_compat_model", modelName)
    }
}
