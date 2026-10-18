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

package com.pickaxe.jcode.artificial.harness.providers

import android.content.Context
import com.pickaxe.jcode.artificial.agents.Agents
import com.pickaxe.jcode.artificial.harness.ChatProvider
import com.pickaxe.jcode.artificial.secrets.ApiKey
import com.pickaxe.jcode.preferences.internal.prefManager

/** Builds a [ChatProvider] for a configured provider id, or null when it is not usable. */
object ChatProviderFactory {

  fun create(context: Context, providerId: String): ChatProvider? {
    val agents = Agents(context)
    return when (providerId) {
      "openai" -> {
        val key = ApiKey.getOpenAIApiKey()
        if (key.isBlank()) null
        else
          OpenAiCompatProvider(
            providerId = "openai",
            providerName = "OpenAI",
            apiKey = key,
            model = modelFor(agents, "openai"),
            baseUrl = "https://api.openai.com/v1",
          )
      }
      "deepseek" -> {
        val key = ApiKey.getDeepseekApiKey()
        if (key.isBlank()) null
        else
          OpenAiCompatProvider(
            providerId = "deepseek",
            providerName = "DeepSeek",
            apiKey = key,
            model = modelFor(agents, "deepseek"),
            baseUrl = "https://api.deepseek.com/v1",
          )
      }
      "grok" -> {
        val key = ApiKey.getGrokApiKey()
        if (key.isBlank()) null
        else
          OpenAiCompatProvider(
            providerId = "grok",
            providerName = "Grok",
            apiKey = key,
            model = modelFor(agents, "grok"),
            baseUrl = "https://api.x.ai/v1",
          )
      }
      "claude" -> {
        val key = ApiKey.getAnthropicApiKey()
        if (key.isBlank()) null
        else AnthropicProvider("claude", "Anthropic", key, modelFor(agents, "claude"))
      }
      "gemini" -> {
        val key = ApiKey.getGeminiApiKey()
        if (key.isBlank()) null
        else GeminiProvider("gemini", "Gemini", key, modelFor(agents, "gemini"))
      }
      "localllm" -> {
        val base = prefManager.getString("local_llm_base_url", "http://localhost:1234").trimEnd('/')
        val versioned = if (base.endsWith("/v1")) base else "$base/v1"
        val model = prefManager.getString("local_llm_model_name", "local-model")
        OpenAiCompatProvider("localllm", "Local LLM", null, model, versioned)
      }
      "openai_compat" -> {
        val base = prefManager.getString("openai_compat_base_url", "").trimEnd('/')
        val model = prefManager.getString("openai_compat_model", "")
        if (base.isBlank() || model.isBlank()) null
        else {
          val versioned = if (base.endsWith("/v1")) base else "$base/v1"
          val key = prefManager.getString("openai_compat_api_key", "")
            ?.takeIf { it.isNotBlank() }
          val name = prefManager.getString("openai_compat_name", "OpenAI 兼容 API")
            ?.takeIf { it.isNotBlank() } ?: "OpenAI 兼容 API"
          OpenAiCompatProvider("openai_compat", name, key, model, versioned)
        }
      }
      else -> null
    }
  }

  private fun modelFor(agents: Agents, providerId: String): String {
    val model = agents.getAgent()
    return if (agents.isValidModelForProvider(model, providerId)) model
    else agents.getModelsForProvider(providerId).firstOrNull() ?: model
  }
}
