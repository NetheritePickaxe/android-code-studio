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

import com.pickaxe.jcode.artificial.harness.HarnessEvent
import com.pickaxe.jcode.artificial.harness.Tool
import com.pickaxe.jcode.artificial.harness.ToolContext
import com.pickaxe.jcode.artificial.harness.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/** Runs a shell command in the project directory and returns its exit code and output. */
class RunCommandTool(private val context: ToolContext) : Tool {

  override val name = "run_command"

  override val description =
    "Run a shell command in the project directory and return its exit code and combined " +
      "stdout/stderr. Use it to build, test, format or inspect the project, for example " +
      "\"./gradlew :app:assembleDebug\" or \"git status --short\". Each call gets a fresh " +
      "login shell, so 'cd' and exported variables do not carry over: chain steps with '&&' " +
      "instead. Long output is truncated in the middle."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("command", stringProp("Shell command to run in the project directory."))
    props.put(
      "timeout",
      integerProp("Seconds to wait before the command is killed. Optional, default 300, max 1800."),
    )
    return objectSchema(props, JSONArray().put("command"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val runner = context.commands ?: return ToolResult.Failure("Command execution is unavailable")
    val command = args.optString("command", "")
    if (command.isBlank()) return ToolResult.Failure("command is required")

    val timeoutSeconds = args.optInt("timeout", DEFAULT_TIMEOUT_SECONDS).coerceIn(1, MAX_TIMEOUT_SECONDS)
    context.emit(HarnessEvent.Status("\$ ${command.lineSequence().first()}"))

    val result =
      try {
        runner.run(command, context.projectRoot, timeoutSeconds * 1000L)
      } catch (e: Exception) {
        return ToolResult.Failure("Command failed to run: ${e.message}")
      }

    val body = truncate(result.output.trimEnd())
    if (result.timedOut) {
      return ToolResult.Failure(
        "Command timed out after ${timeoutSeconds}s and was killed." +
          if (body.isBlank()) "" else "\n\n$body"
      )
    }

    val header = "exit code: ${result.exitCode}"
    val text = if (body.isBlank()) header else "$header\n\n$body"
    return if (result.exitCode == 0) ToolResult.Ok(text) else ToolResult.Failure(text)
  }

  /** Keep the head and the tail: build failures usually matter at one end or the other. */
  private fun truncate(output: String): String {
    if (output.length <= MAX_OUTPUT_CHARS) return output
    val half = MAX_OUTPUT_CHARS / 2
    val omitted = output.length - MAX_OUTPUT_CHARS
    return output.take(half) +
      "\n\n... [$omitted characters omitted] ...\n\n" +
      output.takeLast(half)
  }

  private companion object {
    const val DEFAULT_TIMEOUT_SECONDS = 300
    const val MAX_TIMEOUT_SECONDS = 1_800
    const val MAX_OUTPUT_CHARS = 24_000
  }
}
