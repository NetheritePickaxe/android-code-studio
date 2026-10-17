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

/** Role of a message in the harness conversation. */
enum class Role {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL,
}

/** A tool invocation requested by the model. */
data class ToolCall(
  val id: String,
  val name: String,
  /** Raw JSON object string of the tool arguments. */
  val arguments: String,
)

/** One message in the harness conversation. */
data class ChatMessage(
  val role: Role,
  val content: String? = null,
  val toolCalls: List<ToolCall> = emptyList(),
  val toolCallId: String? = null,
  val toolName: String? = null,
)

/** A single assistant turn returned by a provider. */
data class ChatTurn(
  val text: String? = null,
  val toolCalls: List<ToolCall> = emptyList(),
)
