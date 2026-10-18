package com.pickaxe.jcode.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.pickaxe.jcode.R
import com.pickaxe.jcode.artificial.agents.AIAgentManager
import com.pickaxe.jcode.artificial.agents.Agents
import com.pickaxe.jcode.artificial.dialogs.ProviderSwitchDialog
import com.pickaxe.jcode.managers.CodeCompletionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.pickaxe.jcode.app.BaseApplication

class AIPreferencesFragment(
    private val aiAgent: AIAgentManager,
    private val agents: Agents,
    private val codeCompletionManager: CodeCompletionManager?
) : Fragment() {

    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView
    
    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    
    private var completionStateMonitorJob: Job? = null
    private var isCompletionEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupProviderDropdown()
        setupModelDropdown()
        setupToggles()
        updateCurrentStatus()
        startCompletionStateMonitoring()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentStatus()
        updateProviderDropdownSelection()
        updateModelDropdown()
        syncCodeCompletionToggle()
    }
    
    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)
    }

    private fun setupProviderDropdown() {
        val providerMap = mapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "localllm" to "Local LLM",
            "openai_compat" to "OpenAI 兼容 API"
        )

        val allProviderIds = listOf("gemini", "openai", "claude", "deepseek", "grok", "localllm", "openai_compat")
        val providerNames = allProviderIds.map { providerMap[it] ?: it }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, providerNames)
        providerDropdown.setAdapter(adapter)

        updateProviderDropdownSelection()

        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedProviderId = allProviderIds[position]
            val selectedProviderName = providerNames[position]
            handleProviderChange(selectedProviderId, selectedProviderName)
        }
    }
    
    private fun updateProviderDropdownSelection() {
        val providerMap = mapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "localllm" to "Local LLM",
            "openai_compat" to "OpenAI 兼容 API"
        )

        val currentProviderId = agents.getProvider()
        val currentProviderName = providerMap[currentProviderId] ?: currentProviderId
        providerDropdown.setText(currentProviderName, false)
    }

    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()

        android.util.Log.d("AIPreferences", "Current provider: $currentProvider, model: $currentModel")

        val providerDisplayName = when(currentProvider) {
            "gemini" -> "Google Gemini"
            "openai" -> "OpenAI"
            "claude" -> "Anthropic Claude"
            "deepseek" -> "DeepSeek"
            "grok" -> "xAI Grok"
            "localllm" -> "Local LLM"
            "openai_compat" -> {
                val prefs = BaseApplication.getBaseInstance().prefManager
                prefs.getString("openai_compat_name", "OpenAI 兼容 API") ?: "OpenAI 兼容 API"
            }
            else -> currentProvider.uppercase()
        }

        currentProviderText.text = providerDisplayName

        val modelDisplay = if (currentProvider == "openai_compat") {
            val prefs = BaseApplication.getBaseInstance().prefManager
            prefs.getString("openai_compat_model", currentModel) ?: currentModel
        } else if (currentProvider == "localllm") {
            val prefs = BaseApplication.getBaseInstance().prefManager
            prefs.getString("local_llm_model_name", currentModel) ?: currentModel
        } else {
            currentModel
        }
        currentModelText.text = modelDisplay
    }

    private fun setupModelDropdown() {
        updateModelDropdown()
        
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val currentProvider = agents.getProvider()
            val models = agents.getModelsForProvider(currentProvider)
            
            if (position < models.size) {
                val selectedModel = models[position]
                handleModelChange(selectedModel)
            }
        }
    }

    private fun updateModelDropdown() {
        val currentProvider = agents.getProvider()
        val models = agents.getModelsForProvider(currentProvider)

        if (currentProvider == "openai_compat") {
            val prefs = BaseApplication.getBaseInstance().prefManager
            val model = prefs.getString("openai_compat_model", "") ?: ""
            val modelList = if (model.isNotEmpty()) listOf(model) else listOf("未配置")
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, modelList)
            modelDropdown.setAdapter(adapter)
            modelDropdown.setText(modelList[0], false)
            return
        }

        if (currentProvider == "localllm") {
            val prefs = BaseApplication.getBaseInstance().prefManager
            val model = prefs.getString("local_llm_model_name", "local-model") ?: "local-model"
            val modelList = listOf(model)
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, modelList)
            modelDropdown.setAdapter(adapter)
            modelDropdown.setText(modelList[0], false)
            return
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models.toList())
        modelDropdown.setAdapter(adapter)

        val currentModel = agents.getAgent()
        if (currentModel in models) {
            modelDropdown.setText(currentModel, false)
        } else if (models.isNotEmpty()) {
            modelDropdown.setText(models[0], false)
        }
    }

    private fun setupToggles() {
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            val message = if (isChecked) {
                "Auto-switch enabled"
            } else {
                "Auto-switch disabled"
            }
            showSnackbar(message)
        }
        
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
        
        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            android.util.Log.d("AIPreferences", "Toggle changed to: $isChecked")
            
            isCompletionEnabled = isChecked
            
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("code_completion_enabled", isChecked)
                .apply()
            
            lifecycleScope.launch {
                applyCompletionStateChange(isChecked)
            }
            
            val message = if (isChecked) {
                "✅ Code completion enabled"
            } else {
                "❌ Code completion disabled"
            }
            showSnackbar(message)
        }
    }
    
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        
        completionStateMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(100)
                
                val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                
                if (savedState != isCompletionEnabled) {
                    android.util.Log.d("AIPreferences", "State mismatch detected: saved=$savedState, current=$isCompletionEnabled")
                    isCompletionEnabled = savedState
                    
                    if (codeCompletionToggle.isChecked != savedState) {
                        codeCompletionToggle.isChecked = savedState
                    }
                    
                    applyCompletionStateChange(savedState)
                }
            }
        }
    }
    
    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }
    
    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("AIPreferences", "Applying completion state change: $enabled")
        
        if (enabled) {
            codeCompletionManager?.reattachToCurrentEditor()
            android.util.Log.d("AIPreferences", "Re-enabled code completion")
        } else {
            codeCompletionManager?.cleanup()
            android.util.Log.d("AIPreferences", "Disabled code completion")
        }
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        
        android.util.Log.d("AIPreferences", "Syncing toggle: saved=$savedState")
        
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
    }

    private fun handleProviderChange(providerId: String, providerName: String) {
        android.util.Log.d("AIPreferences", "Switching to provider: $providerId")

        val availableModels = agents.getModelsForProvider(providerId)
        android.util.Log.d("AIPreferences", "Available models for $providerId: ${availableModels.joinToString()}")

        if (providerId == "openai_compat") {
            val prefs = BaseApplication.getBaseInstance().prefManager
            val model = prefs.getString("openai_compat_model", null)
            if (model != null && model.isNotEmpty()) {
                agents.setAgent(model)
            } else {
                agents.setAgent("custom-model")
            }
        } else if (availableModels.isNotEmpty()) {
            val defaultModel = availableModels[0]
            agents.setAgent(defaultModel)
            android.util.Log.d("AIPreferences", "Set default model: $defaultModel")
        }

        agents.setProvider(providerId)

        updateModelDropdown()

        if (aiAgent.setProvider(providerId)) {
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()

            lifecycleScope.launch {
                if (isCompletionEnabled) {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                    android.util.Log.d("AIPreferences", "Reattached completion after provider change")
                }
            }

            showSnackbar("已切换到 $providerName")
        } else {
            val needsConfig = providerId == "openai_compat" || providerId == "localllm"
            showSnackbar(if (needsConfig) "⚠️ 请先配置 $providerName" else "⚠️ $providerName 的 API Key 无效")
        }
    }

    private fun handleModelChange(modelName: String) {
        android.util.Log.d("AIPreferences", "Switching to model: $modelName")
        agents.setAgent(modelName)
        aiAgent.reinitializeWithSelectedModel()
        updateCurrentStatus()
        
        lifecycleScope.launch {
            if (isCompletionEnabled) {
                delay(500)
                codeCompletionManager?.reattachToCurrentEditor()
                android.util.Log.d("AIPreferences", "Reattached completion after model change")
            }
        }
        
        showSnackbar("Model switched to: $modelName")
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}