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
 * Chat provider for Google Gemini.
 *
 * @param baseUrl base URL including the version segment, e.g. `https://generativelanguage.googleapis.com/v1beta`.
 */
class GeminiProvider(
  override val providerId: String = "gemini",
  override val providerName: String = "Gemini",
  private val apiKey: String,
  private val model: String,
  baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : ChatProvider {

  override val supportsTools: Boolean = true
  override val supportsStreaming: Boolean = false

  private val root: String = baseUrl.trimEnd('/')

  override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatTurn =
    withContext(Dispatchers.IO) {
      val body = JSONObject()
      body.put("contents", encodeContents(messages))

      val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content ?: "" }
      if (system.isNotBlank()) {
        body.put(
          "systemInstruction",
          JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
        )
      }
      if (tools.isNotEmpty()) {
        body.put("tools", encodeTools(tools))
      }

      val request =
        Request.Builder()
          .url("$root/models/$model:generateContent?key=$apiKey")
          .addHeader("Content-Type", "application/json")
          .post(body.toString().toRequestBody(JSON_MEDIA))
          .build()

      parse(fetchJson(providerName, request))
    }

  private fun parse(text: String): ChatTurn {
    if (text.isBlank()) throw RuntimeException("$providerName returned an empty response")
    val root = JSONObject(text)
    root.optJSONObject("error")?.let { throw RuntimeException("$providerName error: ${it.optString("message")}") }

    val candidates =
      root.optJSONArray("candidates")
        ?: throw RuntimeException("$providerName response has no candidates: ${text.take(300)}")
    if (candidates.length() == 0) throw RuntimeException("$providerName returned no candidates")

    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
    val sb = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    if (parts != null) {
      for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        part.optString("text", "").takeIf { it.isNotBlank() }?.let { sb.append(it) }
        part.optJSONObject("functionCall")?.let { fc ->
          calls.add(
            ToolCall(
              id = "call_${calls.size}",
              name = fc.optString("name", ""),
              arguments = fc.optJSONObject("args")?.toString() ?: "{}",
            )
          )
        }
      }
    }
    return ChatTurn(sb.toString().takeIf { it.isNotBlank() }, calls)
  }

  private fun encodeContents(messages: List<ChatMessage>): JSONArray {
    val contents = JSONArray()
    for (m in messages) {
      when (m.role) {
        Role.SYSTEM -> {}
        Role.USER ->
          contents.put(
            JSONObject()
              .put("role", "user")
              .put("parts", JSONArray().put(JSONObject().put("text", m.content ?: "")))
          )
        Role.ASSISTANT -> {
          val parts = JSONArray()
          if (!m.content.isNullOrBlank()) parts.put(JSONObject().put("text", m.content))
          for (call in m.toolCalls) {
            parts.put(
              JSONObject()
                .put(
                  "functionCall",
                  JSONObject().put("name", call.name).put("args", argsToJson(call.arguments))
                )
            )
          }
          if (parts.length() > 0) {
            contents.put(JSONObject().put("role", "model").put("parts", parts))
          }
        }
        Role.TOOL -> {
          val parts = JSONArray()
          parts.put(
            JSONObject()
              .put(
                "functionResponse",
                JSONObject()
                  .put("name", m.toolName ?: "")
                  .put("response", JSONObject().put("result", m.content ?: ""))
              )
          )
          contents.put(JSONObject().put("role", "user").put("parts", parts))
        }
      }
    }
    return contents
  }

  private fun encodeTools(tools: List<ToolSpec>): JSONArray {
    val declarations = JSONArray()
    for (t in tools) {
      declarations.put(
        JSONObject()
          .put("name", t.name)
          .put("description", t.description)
          .put("parameters", t.parameters)
      )
    }
    return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
  }
}
