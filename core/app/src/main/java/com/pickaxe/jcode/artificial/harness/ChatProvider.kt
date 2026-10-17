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

/** A model backend. Providers that support tool calling return [ChatTurn.toolCalls]. */
interface ChatProvider {
  val providerId: String
  val providerName: String
  val supportsTools: Boolean

  /** True when [chatStream] delivers the turn incrementally rather than in one piece. */
  val supportsStreaming: Boolean
    get() = false

  /** Send the conversation and return the next assistant turn. */
  suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatTurn

  /**
   * Stream the next assistant turn.
   *
   * Text arrives through [onToken], tool calls through [onToolCall] and the finished turn
   * through [onDone]. Providers that cannot stream override neither this nor
   * [supportsStreaming]: they fall back to [chat] and emit the whole turn at once, so callers
   * only ever see one shape of output.
   */
  suspend fun chatStream(
    messages: List<ChatMessage>,
    tools: List<ToolSpec>,
    onToken: suspend (String) -> Unit,
    onToolCall: suspend (ToolCall) -> Unit,
    onDone: suspend (ChatTurn) -> Unit,
  ) {
    val turn = chat(messages, tools)
    turn.text?.let { onToken(it) }
    turn.toolCalls.forEach { onToolCall(it) }
    onDone(turn)
  }
}
