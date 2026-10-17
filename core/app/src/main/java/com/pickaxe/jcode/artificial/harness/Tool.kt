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

/** A capability the agent can invoke. */
interface Tool {
  /** Unique tool name, as exposed to the model. */
  val name: String

  /** Human readable description shown to the model. */
  val description: String

  /** JSON Schema (type=object) describing the arguments. */
  fun parameters(): JSONObject

  /** Run the tool with the given arguments. */
  suspend fun execute(args: JSONObject): ToolResult
}

/** Outcome of a tool invocation. */
sealed class ToolResult {
  data class Ok(val output: String) : ToolResult()
  data class Failure(val message: String) : ToolResult()
}

/** Immutable tool description handed to providers. */
data class ToolSpec(
  val name: String,
  val description: String,
  val parameters: JSONObject,
)

/** Ordered collection of tools available to a harness. */
class ToolRegistry {
  private val tools = LinkedHashMap<String, Tool>()

  fun register(tool: Tool): ToolRegistry {
    tools[tool.name] = tool
    return this
  }

  fun registerAll(items: Collection<Tool>): ToolRegistry {
    items.forEach { register(it) }
    return this
  }

  fun get(name: String): Tool? = tools[name]

  fun specs(): List<ToolSpec> = tools.values.map { ToolSpec(it.name, it.description, it.parameters()) }

  fun names(): List<String> = tools.keys.toList()
}
