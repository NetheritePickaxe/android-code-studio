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

import com.pickaxe.jcode.artificial.harness.ChatMessage
import com.pickaxe.jcode.artificial.harness.ChatProvider
import com.pickaxe.jcode.artificial.harness.ChatTurn
import com.pickaxe.jcode.artificial.harness.Role
import com.pickaxe.jcode.artificial.harness.ToolCall
import com.pickaxe.jcode.artificial.harness.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat provider for OpenAI-compatible endpoints (OpenAI, DeepSeek, Grok, LM Studio, ...).
 *
 * @param baseUrl base URL including the version segment, e.g. `https://api.openai.com/v1`.
 */
class OpenAiCompatProvider(
  override val providerId: String,
  override val providerName: String,
  private val apiKey: String?,
  private val model: String,
  baseUrl: String,
) : ChatProvider {

  override val supportsTools: Boolean = true
  override val supportsStreaming: Boolean = true

  private val endpoint: String = baseUrl.trimEnd('/') + "/chat/completions"

  override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatTurn =
    withContext(Dispatchers.IO) {
      val body = JSONObject()
      body.put("model", model)
      body.put("messages", encodeMessages(messages))
      if (tools.isNotEmpty()) {
        body.put("tools", encodeTools(tools))
        body.put("tool_choice", "auto")
      }

      val request =
        Request.Builder()
          .url(endpoint)
          .addHeader("Content-Type", "application/json")
          .apply { if (!apiKey.isNullOrBlank()) addHeader("Authorization", "Bearer $apiKey") }
          .post(body.toString().toRequestBody(JSON_MEDIA))
          .build()

      parse(fetchJson(providerName, request))
    }

  private fun parse(text: String): ChatTurn {
    if (text.isBlank()) throw RuntimeException("$providerName returned an empty response")
    val root = JSONObject(text)
    root.optJSONObject("error")?.let { throw RuntimeException("$providerName error: ${it.optString("message")}") }

    val choices = root.optJSONArray("choices")
      ?: throw RuntimeException("$providerName response has no choices: ${text.take(300)}")
    if (choices.length() == 0) throw RuntimeException("$providerName returned no choices")

    val message = choices.getJSONObject(0).optJSONObject("message")
      ?: throw RuntimeException("$providerName response has no message")

    val content = message.optString("content", "").takeIf { it.isNotBlank() && it != "null" }
    val calls = mutableListOf<ToolCall>()
    message.optJSONArray("tool_calls")?.let { arr ->
      for (i in 0 until arr.length()) {
        val call = arr.optJSONObject(i) ?: continue
        val fn = call.optJSONObject("function") ?: continue
        calls.add(
          ToolCall(
            id = call.optString("id", "call_$i"),
            name = fn.optString("name", ""),
            arguments = fn.optString("arguments", "{}"),
          )
        )
      }
    }
    return ChatTurn(content, calls)
  }

  private fun encodeMessages(messages: List<ChatMessage>): JSONArray {
    val arr = JSONArray()
    for (m in messages) {
      val o = JSONObject()
      when (m.role) {
        Role.SYSTEM -> {
          o.put("role", "system")
          o.put("content", m.content ?: "")
        }
        Role.USER -> {
          o.put("role", "user")
          o.put("content", m.content ?: "")
        }
        Role.ASSISTANT -> {
          o.put("role", "assistant")
          o.putOpt("content", m.content)
          if (m.toolCalls.isNotEmpty()) {
            val calls = JSONArray()
            for (call in m.toolCalls) {
              calls.put(
                JSONObject()
                  .put("id", call.id)
                  .put("type", "function")
                  .put(
                    "function",
                    JSONObject().put("name", call.name).put("arguments", call.arguments.ifBlank { "{}" })
                  )
              )
            }
            o.put("tool_calls", calls)
          }
        }
        Role.TOOL -> {
          o.put("role", "tool")
          o.put("tool_call_id", m.toolCallId ?: "")
          o.put("content", m.content ?: "")
        }
      }
      arr.put(o)
    }
    return arr
  }

  private fun encodeTools(tools: List<ToolSpec>): JSONArray {
    val arr = JSONArray()
    for (t in tools) {
      arr.put(
        JSONObject()
          .put("type", "function")
          .put(
            "function",
            JSONObject()
              .put("name", t.name)
              .put("description", t.description)
              .put("parameters", t.parameters)
          )
      )
    }
    return arr
  }
}
