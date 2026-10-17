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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.artificial.tools

import com.pickaxe.jcode.artificial.file.FileWriteResult
import com.pickaxe.jcode.artificial.harness.HarnessEvent
import com.pickaxe.jcode.artificial.harness.Tool
import com.pickaxe.jcode.artificial.harness.ToolContext
import com.pickaxe.jcode.artificial.harness.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/** Reads a text file, returning numbered lines. */
class ReadFileTool(private val context: ToolContext) : Tool {

  override val name = "read_file"
  override val description =
    "Read a text file from the project. Output is prefixed with 1-based line numbers. " +
      "Use offset/limit to page through large files."

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

    val lines =
      try {
        file.readLines()
      } catch (e: Exception) {
        return ToolResult.Failure("Cannot read $path: ${e.message}")
      }

    val offset = args.optInt("offset", 1).coerceAtLeast(1)
    val limit = args.optInt("limit", 400).coerceIn(1, 2000)
    val start = offset - 1
    if (start >= lines.size) {
      return ToolResult.Failure("offset $offset is past the end of $path (${lines.size} lines)")
    }
    val end = minOf(lines.size, start + limit)

    val sb = StringBuilder()
    for (i in start until end) {
      sb.append(i + 1).append('\t').append(lines[i]).append('\n')
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
    "Create a file or replace its entire contents. Prefer edit_file for small changes to " +
      "existing files. Parent directories are created automatically."

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

    val isNew = !file.exists()
    return when (val res = context.fileWriter.writeFile(file.absolutePath, content, createBackup = true)) {
      is FileWriteResult.Success -> {
        val relative = context.relative(file)
        context.emit(HarnessEvent.FileChanged(file.absolutePath, relative, isNew))
        ToolResult.Ok((if (isNew) "Created " else "Updated ") + relative + " (" + content.length + " chars)")
      }
      is FileWriteResult.PermissionDenied -> ToolResult.Failure("Write denied: ${res.reason}")
      is FileWriteResult.Error -> ToolResult.Failure(res.message)
    }
  }
}

/** Replaces an exact substring in an existing file. */
class EditFileTool(private val context: ToolContext) : Tool {

  override val name = "edit_file"
  override val description =
    "Replace an exact substring in an existing file. old_string must match the file exactly, " +
      "including whitespace and indentation. Fails if old_string is not found, or is ambiguous " +
      "(set replace_all=true to replace every occurrence)."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("path", stringProp("File path: absolute or relative to the project root."))
    props.put("old_string", stringProp("Exact text to replace."))
    props.put("new_string", stringProp("Replacement text."))
    props.put("replace_all", booleanProp("Replace every occurrence. Optional, default false."))
    return objectSchema(props, JSONArray().put("path").put("old_string").put("new_string"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val path = args.optString("path", "")
    val oldStr = args.optString("old_string", "")
    val newStr = args.optString("new_string", "")
    val replaceAll = args.optBoolean("replace_all", false)

    if (path.isBlank()) return ToolResult.Failure("path is required")
    if (oldStr.isEmpty()) return ToolResult.Failure("old_string must not be empty")

    val file = context.resolve(path)
    if (!file.exists() || !file.isFile) return ToolResult.Failure("File not found: $path")
    if (!context.withinProject(file)) return ToolResult.Failure("Refusing to edit outside the project: $path")

    val content =
      try {
        file.readText()
      } catch (e: Exception) {
        return ToolResult.Failure("Cannot read $path: ${e.message}")
      }

    val count = countOccurrences(content, oldStr)
    if (count == 0) return ToolResult.Failure("old_string was not found in ${context.relative(file)}")
    if (count > 1 && !replaceAll) {
      return ToolResult.Failure(
        "old_string occurs $count times in ${context.relative(file)}; add more context or set replace_all=true"
      )
    }

    val updated = if (replaceAll) content.replace(oldStr, newStr) else content.replaceFirst(oldStr, newStr)
    return when (val res = context.fileWriter.writeFile(file.absolutePath, updated, createBackup = true)) {
      is FileWriteResult.Success -> {
        val relative = context.relative(file)
        context.emit(HarnessEvent.FileChanged(file.absolutePath, relative, false))
        ToolResult.Ok("Edited $relative ($count replacement" + (if (count == 1) ")" else "s)"))
      }
      is FileWriteResult.PermissionDenied -> ToolResult.Failure("Write denied: ${res.reason}")
      is FileWriteResult.Error -> ToolResult.Failure(res.message)
    }
  }

  private fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = haystack.indexOf(needle)
    while (index >= 0) {
      count++
      index = haystack.indexOf(needle, index + needle.length)
    }
    return count
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
