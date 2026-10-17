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

package com.pickaxe.jcode.artificial.tools

import com.pickaxe.jcode.artificial.harness.AgentHarness
import com.pickaxe.jcode.artificial.harness.ChatMessage
import com.pickaxe.jcode.artificial.harness.HarnessEvent
import com.pickaxe.jcode.artificial.harness.Role
import com.pickaxe.jcode.artificial.harness.SystemPrompt
import com.pickaxe.jcode.artificial.harness.Tool
import com.pickaxe.jcode.artificial.harness.ToolContext
import com.pickaxe.jcode.artificial.harness.ToolRegistry
import com.pickaxe.jcode.artificial.harness.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Runs a task in a child harness and returns its final answer.
 *
 * The child shares the project, the undo log and the cut registers of the parent, so it edits the
 * same files the parent would; what it does not share is the conversation. It gets its own message
 * list seeded with a system prompt that says which kind of agent it is, and its answer is the only
 * thing handed back. [ToolContext.subagentDepth] keeps a child from spawning children forever.
 */
class SubagentTool(private val context: ToolContext) : Tool {

  override val name = "subagent"

  override val description =
    "Run a task in a child agent and get its final answer back. The child has its own " +
      "conversation but works in the same project, so it can read and edit the same files. Use it " +
      "to work on a self-contained subtask in parallel with the main task; give it everything it " +
      "needs in `prompt` — it does not see this conversation."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("agent", stringProp("Kind of agent to run: \"general\" to work, \"explore\" to search only."))
    props.put("description", stringProp("Short 3-5 word label for the task, shown while it runs."))
    props.put("prompt", stringProp("The full task for the child agent."))
    props.put("model", stringProp("Optional provider/model id to run it on. Empty uses the current one."))
    return objectSchema(props, JSONArray().put("prompt"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val factory = context.providerFactory
      ?: return ToolResult.Failure("subagent is unavailable: no provider factory is configured")
    if (context.subagentDepth >= MAX_DEPTH) {
      return ToolResult.Failure("subagent nesting limit reached (max $MAX_DEPTH)")
    }

    val prompt = args.optString("prompt", "")
    if (prompt.isBlank()) return ToolResult.Failure("prompt is required")

    val label = args.optString("description", "").trim().ifBlank { "subtask" }
    val agent = args.optString("agent", "general").trim().ifBlank { "general" }
    val model = args.optString("model", "").trim().takeIf { it.isNotBlank() }

    val provider =
      factory(model)
        ?: return ToolResult.Failure("no provider is configured for this subagent")

    val sessionId = "sub_" + UUID.randomUUID().toString().replace("-", "").take(8)

    // One child context for both the tools and the harness, so the depth guard counts this layer
    // and the grandchild sees depth 2 instead of starting over at 0.
    val childContext = context.childContext()
    val childRegistry = ToolRegistry().registerAll(defaultTools(childContext))

    // The child is asked for nothing but its result, so its answer stays a report rather than a
    // conversation the parent has to keep track of.
    val system =
      SystemPrompt.build(childContext.rootPath(), null, childRegistry.names()) +
        "\n\n# Role\n" +
        "You are a subagent ($sessionId) of the JCode agent, acting as \"$agent\".\n" +
        "Task: $label\n" +
        "You do not see the parent conversation. Answer with what you found and did.\n" +
        if (agent == "explore") "Explore only: report findings, do not modify files.\n" else ""

    val messages = mutableListOf(ChatMessage(Role.SYSTEM, system))

    context.emit(
      HarnessEvent.Status("subagent $sessionId: $label")
    )

    return try {
      val harness = AgentHarness(provider, childRegistry, childContext, messages)
      val answer = withContext(Dispatchers.IO) { harness.run(prompt) }
      ToolResult.Ok("subagent $sessionId finished:\n\n" + answer.ifBlank { "(no output)" })
    } catch (e: Exception) {
      ToolResult.Failure("subagent $sessionId failed: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private companion object {
    const val MAX_DEPTH = 2
  }
}
