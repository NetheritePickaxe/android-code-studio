/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.pickaxe.jcode.artificial.agents

import android.content.Context
import com.pickaxe.jcode.artificial.agents.google.Gemini
import com.pickaxe.jcode.artificial.agents.openai.OpenAI
import com.pickaxe.jcode.artificial.agents.anthropic.Anthropic
import com.pickaxe.jcode.artificial.agents.grok.Grok
import com.pickaxe.jcode.artificial.agents.deepseek.DeepSeek
import com.pickaxe.jcode.artificial.agents.local.LocalLLM
import com.pickaxe.jcode.artificial.dialogs.ProviderSwitchDialog
import com.pickaxe.jcode.artificial.file.AIFileWriter
import com.pickaxe.jcode.artificial.file.FileWriteResult
import com.pickaxe.jcode.artificial.harness.AgentHarness
import com.pickaxe.jcode.artificial.harness.ChatMessage
import com.pickaxe.jcode.artificial.harness.EditRegisters
import com.pickaxe.jcode.artificial.harness.HarnessEvent
import com.pickaxe.jcode.artificial.harness.ModificationLog
import com.pickaxe.jcode.artificial.harness.ToolContext
import com.pickaxe.jcode.artificial.harness.ToolRegistry
import com.pickaxe.jcode.artificial.harness.providers.ChatProviderFactory
import com.pickaxe.jcode.artificial.permissions.AIPermissionManager
import com.pickaxe.jcode.artificial.project.awareness.ProjectData
import com.pickaxe.jcode.artificial.secrets.ApiKey
import com.pickaxe.jcode.artificial.tools.TermuxCommandRunner
import com.pickaxe.jcode.artificial.tools.defaultTools
import com.pickaxe.jcode.utils.ProjectHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class AIAgentManager(private val context: Context) {

    private val permissionManager = AIPermissionManager(context)
    private var currentProjectRoot: File? = null
    private var currentProviderId: String = "gemini"
    private var currentAgent: AIAgent? = null
    private val providerSwitchDialog = ProviderSwitchDialog(context)
    private val modifications = ModificationLog()

    /** Cut lines that a later edit can paste, shared by every request of this session. */
    private val registers = EditRegisters()

    /** Conversation kept across requests, so a follow-up keeps the earlier context. */
    private val conversation = mutableListOf<ChatMessage>()

    init {
        Gemini.registerAgent()
        OpenAI.registerAgent()
        Anthropic.registerAgent()
        Grok.registerAgent()
        DeepSeek.registerAgent()
        LocalLLM.registerAgent()
        
        permissionManager.setFileWriteEnabled(true)
        permissionManager.setRequireConfirmation(false)
        
        setProvider(currentProviderId)
    }
    
    fun getCurrentAgent(): AIAgent? = currentAgent

    fun setProvider(providerId: String): Boolean {
        android.util.Log.d("AIAgentManager", "setProvider called with: $providerId")
        
        val factory = AIAgentRegistry.getFactory(providerId)
        if (factory == null) {
            android.util.Log.e("AIAgentManager", "No factory found for provider: $providerId")
            return false
        }
        
        if (!factory.hasValidApiKey()) {
            android.util.Log.e("AIAgentManager", "No valid API key for provider: $providerId")
            return false
        }
        
        currentProviderId = providerId
        currentAgent = factory.create(context)
        android.util.Log.d("AIAgentManager", "Agent created: ${currentAgent != null}")
        
        factory.getApiKey()?.let { apiKey ->
            android.util.Log.d("AIAgentManager", "Initializing agent with API key")
            currentAgent?.initialize(apiKey, context)
            currentAgent?.setContext(context)
            
            currentProjectRoot?.let { root ->
                val projectData = ProjectData(context)
                val projectTree = projectData.showProjectTree(root)
                currentAgent?.setProjectData(projectTree)
            }
            
            android.util.Log.d("AIAgentManager", "Agent initialized: ${currentAgent?.isInitialized()}")
        }
        
        return currentAgent?.isInitialized() ?: false
    }

    fun getCurrentProviderId(): String = currentProviderId
    
    fun getCurrentProviderName(): String {
        return currentAgent?.providerName ?: "Unknown"
    }
    
    fun getAvailableProviders(): List<ProviderInfo> {
        return AIAgentRegistry.getAvailableProviders().mapNotNull { providerId ->
            val factory = AIAgentRegistry.getFactory(providerId)
            val agent = factory?.create(context)
            agent?.let {
                ProviderInfo(
                    id = it.providerId,
                    name = it.providerName,
                    isAvailable = factory.hasValidApiKey()
                )
            }
        }
    }

    fun setProjectRoot(projectPath: String): Boolean {
        val projectRoot = File(projectPath)
        if (!projectRoot.exists()) return false

        currentProjectRoot = projectRoot
        val projectData = ProjectData(context)
        val projectTree = projectData.showProjectTree(projectRoot)

        currentAgent?.setProjectData(projectTree)
        permissionManager.addAllowedDirectory(projectRoot.absolutePath)
        conversation.clear()
        registers.clear()

        return true
    }

    fun clearConversation() {
        currentAgent?.clearConversation()
        conversation.clear()
    }

    suspend fun executeRequest(userRequest: String, callback: AIAgentCallback) {
        val root = currentProjectRoot ?: runCatching { ProjectHelper.getProjectRoot() }.getOrNull()
        if (root == null || !root.exists()) {
            callback.onError("No project is open.\n\nOpen a project before using the AI agent.")
            return
        }
        permissionManager.addAllowedDirectory(root.absolutePath)

        val fileWriter = AIFileWriter(context)
        val changedFiles = LinkedHashMap<String, Boolean>()
        val toolContext =
            ToolContext(root, fileWriter, modifications, TermuxCommandRunner(context), registers) { event ->
                handleEvent(event, callback, changedFiles)
            }
        val registry = ToolRegistry().registerAll(defaultTools(toolContext))

        var providerId = currentProviderId
        var providerSwitched = false

        while (true) {
            val provider =
                ChatProviderFactory.create(context, providerId)
                    ?: ChatProviderFactory.create(context, Agents(context).getProvider())
            if (provider == null) {
                callback.onError(
                    "No API key configured for '$providerId'.\n\n" +
                        "Add an API key in the AI agent settings and try again."
                )
                return
            }

            try {
                callback.onProcessing("Working…")
                val harness = AgentHarness(provider, registry, toolContext, conversation)
                val response = withContext(Dispatchers.IO) { harness.run(userRequest) }
                finish(response, changedFiles, callback)
                return
            } catch (e: Exception) {
                android.util.Log.e("AIAgentManager", "Harness failed: ${e.message}", e)

                if (!providerSwitched && isProviderSwitchError(e)) {
                    val alternative = getAlternativeProvider(providerId)
                    if (alternative != null && providerSwitchDialog.isAutoSwitchEnabled()) {
                        callback.onProcessing("⚠️ ${e.message?.take(160) ?: "Request failed"}")
                        callback.onProcessing("🔄 Auto-switching to another provider…")
                        providerId = alternative
                        providerSwitched = true
                        continue
                    }
                }

                callback.onError(formatErrorMessage(e))
                return
            }
        }
    }

    private fun finish(
        response: String,
        changedFiles: Map<String, Boolean>,
        callback: AIAgentCallback
    ) {
        if (changedFiles.isEmpty()) {
            callback.onTextResponse(response, ModificationSummary(0, 0, 0, 0, 0, emptyList()))
            return
        }

        val results =
            changedFiles.map { (path, isNew) ->
                ModificationResult(
                    filePath = path,
                    content = "",
                    success = true,
                    message = if (isNew) "Created" else "Modified",
                    isNewFile = isNew,
                )
            }
        callback.onSuccess(response, results, createSummary(results))
    }

    private fun handleEvent(
        event: HarnessEvent,
        callback: AIAgentCallback,
        changedFiles: MutableMap<String, Boolean>
    ) {
        when (event) {
            is HarnessEvent.AssistantText -> callback.onProcessing(event.text)
            is HarnessEvent.Status -> callback.onProcessing(event.message)
            is HarnessEvent.ToolStarted -> callback.onProcessing("→ ${event.summary}")
            is HarnessEvent.ToolFinished ->
                if (!event.ok) callback.onProcessing("⚠️ ${event.summary}")
            is HarnessEvent.Failure -> callback.onProcessing("⚠️ ${event.message}")
            is HarnessEvent.FileChanged -> {
                changedFiles[event.path] = event.isNew
                val name = File(event.path).name
                callback.onFileModifying(event.path, name)
                callback.onFileModified(event.path, name, true)
            }
        }
    }

    private fun isProviderSwitchError(error: Throwable): Boolean {
        val message = (error.message ?: "").lowercase()
        return message.contains("429") ||
            message.contains("rate limit") ||
            message.contains("quota") ||
            message.contains("insufficient") ||
            message.contains("invalid api key") ||
            message.contains("401") ||
            message.contains("403")
    }

    private fun getAlternativeProvider(exclude: String = currentProviderId): String? {
        val availableProviders = AIAgentRegistry.getAvailableProviders()
        return availableProviders.firstOrNull { it != exclude }
    }

    private fun formatErrorMessage(error: Throwable): String {
        val errorMessage = error.message ?: "Unknown error occurred"
        val stackTrace = error.stackTraceToString().take(500)
        val providerName = currentAgent?.providerName ?: "Unknown"
        
        return when (error) {
            is com.pickaxe.jcode.artificial.exceptions.RateLimitException -> 
                "⚠️ RATE LIMIT EXCEEDED\n\nThe API rate limit has been exceeded.\nPlease wait a few minutes before trying again.\n\nDetails: $errorMessage"
            is com.pickaxe.jcode.artificial.exceptions.QuotaExceededException -> 
                "⚠️ QUOTA EXCEEDED\n\nYour API quota has been exhausted.\nPlease check your billing or upgrade your plan.\n\nDetails: $errorMessage"
            is com.pickaxe.jcode.artificial.exceptions.InsufficientBalanceException -> 
                "💳 INSUFFICIENT BALANCE\n\nYour account balance is too low to process this request.\nPlease add credits or upgrade your plan.\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.pickaxe.jcode.artificial.exceptions.InvalidApiKeyException -> 
                "❌ INVALID API KEY\n\nThe API key is invalid or expired.\nPlease update your API key in the configuration.\n\nDetails: $errorMessage"
            is java.net.UnknownHostException ->
                "🌐 NETWORK ERROR\n\nCould not connect to the API server.\nPlease check your internet connection.\n\nDetails: $errorMessage"
            is java.net.SocketTimeoutException ->
                "⏱️ TIMEOUT ERROR\n\nThe request took too long to complete.\nPlease try again.\n\nDetails: $errorMessage"
            is org.json.JSONException ->
                "📄 JSON PARSING ERROR\n\nFailed to parse API response.\nThe API may be experiencing issues.\n\nDetails: $errorMessage"
            else -> 
                "❌ ERROR OCCURRED\n\nProvider: $providerName\nError Type: ${error.javaClass.simpleName}\n\nMessage: $errorMessage\n\nStack Trace (first 500 chars):\n$stackTrace"
        }
    }

    fun showProviderErrorDialogFromFragment(
        activity: android.app.Activity,
        errorMessage: String,
        onProviderSelected: (String) -> Unit
    ) {
        val currentProviderName = currentAgent?.providerName ?: "Unknown"
        val availableProviders = getAvailableProviders()
            .filter { it.id != currentProviderId && it.isAvailable }
            .map { Pair(it.id, it.name) }
        
        providerSwitchDialog.showProviderErrorDialog(
            currentProviderName,
            errorMessage,
            availableProviders,
            onProviderSelected = { providerId ->
                setProvider(providerId)
                val agents = Agents(context)
                val availableModels = agents.getModelsForProvider(providerId)
                if (availableModels.isNotEmpty()) {
                    agents.setAgent(availableModels[0])
                }
                reinitializeWithSelectedModel()
                onProviderSelected(providerId)
            },
            onEnableAutoSwitch = {
                val alternativeProvider = getAlternativeProvider()
                if (alternativeProvider != null) {
                    setProvider(alternativeProvider)
                    val agents = Agents(context)
                    val availableModels = agents.getModelsForProvider(alternativeProvider)
                    if (availableModels.isNotEmpty()) {
                        agents.setAgent(availableModels[0])
                    }
                    reinitializeWithSelectedModel()
                }
            }
        )
    }
    
    fun isAutoSwitchEnabled(): Boolean {
        return providerSwitchDialog.isAutoSwitchEnabled()
    }
    
    fun setAutoSwitch(enabled: Boolean) {
        providerSwitchDialog.setAutoSwitch(enabled)
    }

    private fun createSummary(results: List<ModificationResult>): ModificationSummary {
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val newFiles = results.count { it.isNewFile }
        val modifiedFiles = results.count { !it.isNewFile }

        val fileDetails = results.map { result ->
            FileDetail(
                fileName = File(result.filePath).name,
                filePath = result.filePath,
                status = if (result.success) FileStatus.SUCCESS else FileStatus.FAILED,
                changeType = if (result.isNewFile) ChangeType.CREATED else ChangeType.MODIFIED
            )
        }

        return ModificationSummary(
            totalFiles = results.size,
            successfulFiles = successful,
            failedFiles = failed,
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            fileDetails = fileDetails
        )
    }

    fun undoLastModification(): Boolean {
        val entry = modifications.removeLast() ?: return false
        return try {
            val file = File(entry.filePath)
            if (entry.previousContent == null) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(entry.previousContent)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("AIAgentManager", "Undo failed: ${e.message}", e)
            false
        }
    }

    fun reinitializeWithSelectedModel() {
        val factory = AIAgentRegistry.getFactory(currentProviderId)
        factory?.getApiKey()?.let { apiKey ->
            currentAgent?.reinitializeWithNewModel(apiKey, context)
        }
    }

    fun getCurrentModelName(): String {
        val agents = Agents(context)
        return agents.getAgent()
    }
    
    fun getConversationHistory(): List<UnifiedModificationAttempt> {
        return modifications.all().map {
            UnifiedModificationAttempt(
                timestamp = it.timestamp,
                filePath = it.filePath,
                previousContent = it.previousContent,
                newContent = it.newContent,
                attemptNumber = 0,
                success = true
            )
        }
    }

    interface AIAgentCallback {
        fun onProcessing(message: String)
        fun onFileModifying(filePath: String, fileName: String)
        fun onFileModified(filePath: String, fileName: String, success: Boolean)
        fun onSuccess(response: String, modifications: List<ModificationResult>, summary: ModificationSummary)
        fun onTextResponse(response: String, summary: ModificationSummary)
        fun onError(message: String)
        fun onRetry(attemptNumber: Int, message: String)
    }

    data class ModificationResult(
        val filePath: String,
        val content: String,
        val success: Boolean,
        val message: String,
        val isNewFile: Boolean = false
    )

    data class ModificationSummary(
        val totalFiles: Int,
        val successfulFiles: Int,
        val failedFiles: Int,
        val newFiles: Int,
        val modifiedFiles: Int,
        val fileDetails: List<FileDetail>
    )

    data class FileDetail(
        val fileName: String,
        val filePath: String,
        val status: FileStatus,
        val changeType: ChangeType
    )

    enum class FileStatus { SUCCESS, FAILED }
    enum class ChangeType { CREATED, MODIFIED }
    
    data class ProviderInfo(
        val id: String,
        val name: String,
        val isAvailable: Boolean
    )
}

data class BaseFileModification(
    val filePath: String,
    val content: String,
    val writeResult: FileWriteResult
)

data class UnifiedModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)