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

import com.pickaxe.jcode.artificial.file.FileWriteResult
import com.pickaxe.jcode.artificial.harness.HarnessEvent
import com.pickaxe.jcode.artificial.harness.Tool
import com.pickaxe.jcode.artificial.harness.ToolContext
import com.pickaxe.jcode.artificial.harness.ToolResult
import com.pickaxe.jcode.artificial.tools.hashline.FileTag
import com.pickaxe.jcode.artificial.tools.hashline.HashlineException
import com.pickaxe.jcode.artificial.tools.hashline.HashlineParser
import com.pickaxe.jcode.artificial.tools.hashline.HashlinePatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a text file.
 *
 * The first line is the `[path#TAG]` header an edit has to copy; the tag fingerprints the whole
 * file, so a paged read still yields a tag a patch can anchor to.
 */
class ReadFileTool(private val context: ToolContext) : Tool {

  override val name = "read_file"
  override val description =
    "Read a text file from the project. The first line is the [path#TAG] header required by " +
      "edit_file; the rest are lines prefixed with their 1-based number. Use offset/limit to " +
      "page through large files."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("path", stringProp("File path: absolute or relative to the project root."))
    props.put("offset", integerProp("First line to return (1-based). Optional."))
    props.put("limit", integerProp("Maximum number of lines. Optional, default 400."))
    return objectSchema(props, JSONArray().put("path"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val path = args.optString("path", "")
    if (path.isBlank()) return ToolResult.Failure("path is required")
    val file = context.resolve(path)
    if (!file.exists() || !file.isFile) return ToolResult.Failure("File not found: $path")

    val text =
      try {
        file.readText()
      } catch (e: Exception) {
        return ToolResult.Failure("Cannot read $path: ${e.message}")
      }
    val lines = text.lines()

    val offset = args.optInt("offset", 1).coerceAtLeast(1)
    val limit = args.optInt("limit", 400).coerceIn(1, 2000)
    val start = offset - 1
    if (start >= lines.size) {
      return ToolResult.Failure("offset $offset is past the end of $path (${lines.size} lines)")
    }
    val end = minOf(lines.size, start + limit)

    val sb = StringBuilder()
    sb.append(FileTag.header(context.display(file), text)).append('\n')
    for (i in start until end) {
      sb.append(i + 1).append(':').append(lines[i]).append('\n')
    }
    if (end < lines.size) {
      sb.append("... ").append(lines.size - end).append(" more lines (use offset=").append(end + 1).append(")")
    }
    return ToolResult.Ok(sb.toString())
  }
}

/** Creates a file or replaces its whole contents. */
class WriteFileTool(private val context: ToolContext) : Tool {

  override val name = "write_file"
  override val description =
    "Create a file or replace its entire contents. Use it for new files and full rewrites; " +
      "change existing files with edit_file. Parent directories are created automatically."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("path", stringProp("File path: absolute or relative to the project root."))
    props.put("content", stringProp("Full file contents to write."))
    return objectSchema(props, JSONArray().put("path").put("content"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val path = args.optString("path", "")
    if (path.isBlank()) return ToolResult.Failure("path is required")
    if (!args.has("content")) return ToolResult.Failure("content is required")
    val content = args.optString("content", "")

    val file = context.resolve(path)
    if (!context.withinProject(file)) return ToolResult.Failure("Refusing to write outside the project: $path")

    val exists = file.exists()
    val previousContent = if (exists) runCatching { file.readText() }.getOrNull() else null
    return when (val res = context.fileWriter.writeFile(file.absolutePath, content, createBackup = true)) {
      is FileWriteResult.Success -> {
        val relative = context.display(file)
        context.modifications.record(file.absolutePath, previousContent, content)
        context.emit(HarnessEvent.FileChanged(file.absolutePath, relative, !exists))
        ToolResult.Ok(
          (if (!exists) "Created " else "Updated ") + relative + "\n" + FileTag.header(relative, content)
        )
      }
      is FileWriteResult.PermissionDenied -> ToolResult.Failure("Write denied: ${res.reason}")
      is FileWriteResult.Error -> ToolResult.Failure(res.message)
    }
  }
}

/**
 * Applies a hashline patch: line anchored ops that carry the tag of the content they were built
 * from, so a patch against a file that has moved on is refused instead of corrupting it.
 */
class EditFileTool(private val context: ToolContext) : Tool {

  override val name = "edit_file"
  override val description =
    """
    Change existing files with a hashline patch. Each section names one file and the tag
    read_file printed for it; every line number is a line of that read, never re-based.

    [path#TAG]
    PUT 3.=7:
    +first replacement line
    +second replacement line

    Locators
      3.=7  lines 3 through 7 (3.=3 for a single line)
      3*    the block that opens at line 3
      <3    before line 3 (<1 is the head of the file)
      >3    after line 3
      >3*   after the block at line 3
      >$    end of file

    Ops
      PUT <locator>:        replace the locator with the + body
      PUT <locator> @name   paste register @name over the locator (no body)
      PUT <gap>             paste the anonymous register at a gap
      CUT <range|block> [@name]  delete those lines, capturing them
      REM                   delete the file
      MV <dest>             rename the file after this section's edits

    Rules
      - The tag is copied from read_file verbatim. Never invent or increment one; a stale tag is
        refused and the reply carries the current one, so re-read and retry.
      - Body lines start with +; a lone + is a blank line and ++ produces a literal +.
      - Delete with CUT, never with an empty PUT; send only the lines you change.
      - `*** Begin Patch` and `*** End Patch` are accepted and ignored.
    """.trimIndent()

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("patch", stringProp("Hashline patch text: one [path#TAG] section per file."))
    return objectSchema(props, JSONArray().put("patch"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val input = args.optString("patch", "")
    if (input.isBlank()) return ToolResult.Failure("patch is required")

    val sections =
      try {
        HashlineParser.parse(input)
      } catch (e: HashlineException) {
        return ToolResult.Failure(e.message ?: "malformed patch")
      }
    if (sections.isEmpty()) return ToolResult.Failure("no [path#TAG] section found in the patch")

    val plans =
      try {
        HashlinePatcher.plan(sections, context, context.registers)
      } catch (e: HashlineException) {
        return ToolResult.Failure(e.message ?: "malformed patch")
      }

    val report = StringBuilder()
    for (plan in plans) {
      when (val outcome = apply(plan)) {
        is ToolResult.Failure -> return outcome
        is ToolResult.Ok -> report.append(outcome.output).append('\n')
      }
    }
    return ToolResult.Ok(report.toString().trimEnd())
  }

  private fun apply(plan: HashlinePatcher.Plan): ToolResult {
    val display = plan.display
    val current = plan.updated ?: plan.original

    if (plan.updated != null && plan.updated != plan.original) {
      when (val res = context.fileWriter.writeFile(plan.file.absolutePath, plan.updated, createBackup = true)) {
        is FileWriteResult.Success -> {
          context.modifications.record(plan.file.absolutePath, plan.original, plan.updated)
          context.emit(HarnessEvent.FileChanged(plan.file.absolutePath, display, false))
        }
        is FileWriteResult.PermissionDenied -> return ToolResult.Failure("Write denied: ${res.reason}")
        is FileWriteResult.Error -> return ToolResult.Failure(res.message)
      }
    }

    val destination = plan.moveTo
    if (destination != null) {
      val resolved = context.display(destination)
      destination.parentFile?.mkdirs()
      if (!plan.file.renameTo(destination)) {
        try {
          destination.writeText(current)
        } catch (e: Exception) {
          return ToolResult.Failure("Cannot move $display to $resolved: ${e.message}")
        }
        if (!plan.file.delete()) {
          return ToolResult.Failure("Moved $display to $resolved but could not remove the original")
        }
      }
      context.modifications.record(plan.file.absolutePath, current, "")
      context.modifications.record(destination.absolutePath, null, current)
      context.emit(HarnessEvent.FileChanged(destination.absolutePath, resolved, false))
      val moved = if (plan.updated != null && plan.updated != plan.original) {
        "Updated $display and moved it to $resolved"
      } else {
        "Moved $display to $resolved"
      }
      return ToolResult.Ok(moved + "\n" + FileTag.header(resolved, current))
    }

    if (plan.updated == null) {
      val deleted = plan.file.delete()
      if (!deleted) return ToolResult.Failure("Cannot delete $display")
      context.modifications.record(plan.file.absolutePath, plan.original, "")
      context.emit(HarnessEvent.FileChanged(plan.file.absolutePath, display, false))
      return ToolResult.Ok("Deleted $display")
    }

    if (plan.updated == plan.original) {
      return ToolResult.Ok(FileTag.header(display, current) + " unchanged")
    }
    return ToolResult.Ok(FileTag.header(display, current) + " updated")
  }
}

internal fun stringProp(description: String): JSONObject =
  JSONObject().put("type", "string").put("description", description)

internal fun integerProp(description: String): JSONObject =
  JSONObject().put("type", "integer").put("description", description)

internal fun booleanProp(description: String): JSONObject =
  JSONObject().put("type", "boolean").put("description", description)

internal fun objectSchema(props: JSONObject, required: JSONArray): JSONObject =
  JSONObject().put("type", "object").put("properties", props).put("required", required)
