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

package com.pickaxe.jcode.artificial.harness

import org.json.JSONObject

/**
 * A hand-written, provider-agnostic tool-calling agent loop.
 *
 * Each iteration asks the provider for the next turn. If the turn contains tool calls, they are
 * executed against [registry] and their output is fed back as tool messages; otherwise the
 * assistant turn is the final answer.
 */
class AgentHarness(
  private val provider: ChatProvider,
  private val registry: ToolRegistry,
  private val context: ToolContext,
  private val messages: MutableList<ChatMessage> = mutableListOf(),
  private val openFile: String? = null,
  private val maxSteps: Int = 40,
) {
  /** Drop the conversation, keeping the agent usable for a new task. */
  fun reset() = messages.clear()

  fun messageCount(): Int = messages.size

  /** Run one user request to completion and return the assistant's final text. */
  suspend fun run(userMessage: String): String {
    if (messages.isEmpty()) {
      messages.add(ChatMessage(Role.SYSTEM, SystemPrompt.build(context.rootPath(), openFile, registry.names())))
    }
    messages.add(ChatMessage(Role.USER, userMessage))

    var step = 0
    var lastText = ""
    while (step < maxSteps) {
      step++
      val turn = provider.chat(messages, registry.specs())

      if (!turn.text.isNullOrBlank()) {
        lastText = turn.text
        context.emit(HarnessEvent.AssistantText(turn.text!!))
      }

      if (turn.toolCalls.isEmpty()) {
        messages.add(ChatMessage(Role.ASSISTANT, turn.text))
        return turn.text.orEmpty()
      }

      context.emit(HarnessEvent.Status("step $step: ${turn.toolCalls.size} tool call(s)"))
      messages.add(ChatMessage(Role.ASSISTANT, turn.text, toolCalls = turn.toolCalls))

      for (call in turn.toolCalls) {
        val output = runTool(call)
        messages.add(
          ChatMessage(
            role = Role.TOOL,
            content = output,
            toolCallId = call.id,
            toolName = call.name,
          )
        )
      }
    }

    context.emit(HarnessEvent.Status("Reached the $maxSteps step limit."))
    return lastText.ifBlank { "Stopped: reached the step limit without a final answer." }
  }

  private suspend fun runTool(call: ToolCall): String {
    val tool = registry.get(call.name)
    if (tool == null) {
      context.emit(HarnessEvent.ToolFinished(call.name, false, "unknown tool"))
      return "ERROR: unknown tool '${call.name}'"
    }

    context.emit(HarnessEvent.ToolStarted(call.name, summaryOf(call)))
    val result =
      try {
        tool.execute(parseArgs(call.arguments))
      } catch (e: Exception) {
        ToolResult.Failure(e.message ?: e.javaClass.simpleName)
      }

    return when (result) {
      is ToolResult.Ok -> {
        context.emit(HarnessEvent.ToolFinished(call.name, true, summaryOf(call)))
        result.output
      }
      is ToolResult.Failure -> {
        context.emit(HarnessEvent.ToolFinished(call.name, false, result.message))
        "ERROR: ${result.message}"
      }
    }
  }

  private fun parseArgs(raw: String): org.json.JSONObject =
    if (raw.isBlank()) org.json.JSONObject() else org.json.JSONObject(raw)

  private fun summaryOf(call: ToolCall): String {
    val path =
      runCatching {
        val args = parseArgs(call.arguments)
        args.optString("path", "").ifBlank {
          args.optString("pattern", "")
        }
      }.getOrDefault("")
    return if (path.isBlank()) call.name else "${call.name} $path"
  }
}
