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
 * Chat provider for Anthropic Claude (Messages API).
 *
 * @param baseUrl base URL including the version segment, e.g. `https://api.anthropic.com/v1`.
 */
class AnthropicProvider(
  override val providerId: String = "claude",
  override val providerName: String = "Anthropic",
  private val apiKey: String,
  private val model: String,
  private val maxTokens: Int = 8192,
  baseUrl: String = "https://api.anthropic.com/v1",
) : ChatProvider {

  override val supportsTools: Boolean = true
  override val supportsStreaming: Boolean = false

  private val endpoint: String = baseUrl.trimEnd('/') + "/messages"

  override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatTurn =
    withContext(Dispatchers.IO) {
      val body = JSONObject()
      body.put("model", model)
      body.put("max_tokens", maxTokens)

      val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content ?: "" }
      if (system.isNotBlank()) body.put("system", system)

      body.put("messages", encodeMessages(messages))
      if (tools.isNotEmpty()) {
        body.put("tools", encodeTools(tools))
      }

      val request =
        Request.Builder()
          .url(endpoint)
          .addHeader("Content-Type", "application/json")
          .addHeader("x-api-key", apiKey)
          .addHeader("anthropic-version", "2023-06-01")
          .post(body.toString().toRequestBody(JSON_MEDIA))
          .build()

      parse(fetchJson(providerName, request))
    }

  private fun parse(text: String): ChatTurn {
    if (text.isBlank()) throw RuntimeException("$providerName returned an empty response")
    val root = JSONObject(text)
    root.optJSONObject("error")?.let { throw RuntimeException("$providerName error: ${it.optString("message")}") }

    val content =
      root.optJSONArray("content")
        ?: throw RuntimeException("$providerName response has no content: ${text.take(300)}")

    val sb = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    for (i in 0 until content.length()) {
      val block = content.optJSONObject(i) ?: continue
      when (block.optString("type")) {
        "text" -> block.optString("text", "").takeIf { it.isNotBlank() }?.let { sb.append(it) }
        "tool_use" ->
          calls.add(
            ToolCall(
              id = block.optString("id", "toolu_$i"),
              name = block.optString("name", ""),
              arguments = block.optJSONObject("input")?.toString() ?: "{}",
            )
          )
      }
    }
    return ChatTurn(sb.toString().takeIf { it.isNotBlank() }, calls)
  }

  private fun encodeMessages(messages: List<ChatMessage>): JSONArray {
    val out = JSONArray()
    var pendingToolResults: JSONArray? = null

    for (m in messages) {
      when (m.role) {
        Role.SYSTEM -> {}
        Role.USER -> {
          pendingToolResults = null
          out.put(
            JSONObject()
              .put("role", "user")
              .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", m.content ?: ""))
              )
          )
        }
        Role.ASSISTANT -> {
          pendingToolResults = null
          val blocks = JSONArray()
          if (!m.content.isNullOrBlank()) {
            blocks.put(JSONObject().put("type", "text").put("text", m.content))
          }
          for (call in m.toolCalls) {
            blocks.put(
              JSONObject()
                .put("type", "tool_use")
                .put("id", call.id)
                .put("name", call.name)
                .put("input", argsToJson(call.arguments))
            )
          }
          if (blocks.length() > 0) {
            out.put(JSONObject().put("role", "assistant").put("content", blocks))
          }
        }
        Role.TOOL -> {
          val block =
            JSONObject()
              .put("type", "tool_result")
              .put("tool_use_id", m.toolCallId ?: "")
              .put("content", m.content ?: "")
          val existing = pendingToolResults
          if (existing != null) {
            existing.put(block)
          } else {
            val results = JSONArray().put(block)
            pendingToolResults = results
            out.put(JSONObject().put("role", "user").put("content", results))
          }
        }
      }
    }
    return out
  }

  private fun encodeTools(tools: List<ToolSpec>): JSONArray {
    val arr = JSONArray()
    for (t in tools) {
      arr.put(
        JSONObject()
          .put("name", t.name)
          .put("description", t.description)
          .put("input_schema", t.parameters)
      )
    }
    return arr
  }
}
