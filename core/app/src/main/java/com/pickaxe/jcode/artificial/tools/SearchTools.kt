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

import com.pickaxe.jcode.artificial.harness.Tool
import com.pickaxe.jcode.artificial.harness.ToolContext
import com.pickaxe.jcode.artificial.harness.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lists files under a directory, optionally filtered by glob. */
class ListFilesTool(private val context: ToolContext) : Tool {

  override val name = "list_files"
  override val description =
    "List files under a directory, optionally filtered by a glob (e.g. \"**/*.kt\"). " +
      "Paths are relative to the project root. Build output and VCS directories are skipped."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("path", stringProp("Directory to list. Optional, defaults to the project root."))
    props.put("pattern", stringProp("Glob to filter paths, e.g. \"**/*.java\". Optional."))
    props.put("max", integerProp("Maximum results. Optional, default 200."))
    return objectSchema(props, JSONArray())
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val path = args.optString("path", "").ifBlank { "." }
    val pattern = args.optString("pattern", "").ifBlank { null }
    val max = args.optInt("max", 200).coerceIn(1, 2000)

    val dir = context.resolve(path)
    if (!dir.exists() || !dir.isDirectory) return ToolResult.Failure("Not a directory: $path")

    val results = mutableListOf<String>()
    dir.walkTopDown()
      .onEnter { it.isDirectory && !isSkipped(it) }
      .filter { it.isFile }
      .forEach { file ->
        val relative = context.relative(file)
        if (pattern == null || Glob.matches(pattern, relative)) {
          if (results.size < max) results.add(relative)
        }
      }

    if (results.isEmpty()) return ToolResult.Ok("No files found.")
    val sb = StringBuilder()
    results.forEach { sb.append(it).append('\n') }
    if (results.size >= max) sb.append("... truncated at $max results")
    return ToolResult.Ok(sb.toString())
  }
}

/** Searches file contents with a regular expression. */
class GrepTool(private val context: ToolContext) : Tool {

  override val name = "grep"
  override val description =
    "Search file contents with a Java regular expression. Returns matching lines as " +
      "\"path:line: text\". Skips binaries, build output and VCS directories."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("pattern", stringProp("Regular expression to search for."))
    props.put("path", stringProp("Directory to search. Optional, defaults to the project root."))
    props.put("include", stringProp("Glob to restrict files, e.g. \"**/*.kt\". Optional."))
    props.put("max", integerProp("Maximum matches. Optional, default 100."))
    return objectSchema(props, JSONArray().put("pattern"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val pattern = args.optString("pattern", "")
    if (pattern.isBlank()) return ToolResult.Failure("pattern is required")
    val regex =
      try {
        Regex(pattern)
      } catch (e: Exception) {
        return ToolResult.Failure("Invalid regex: ${e.message}")
      }

    val path = args.optString("path", "").ifBlank { "." }
    val dir = context.resolve(path)
    if (!dir.exists() || !dir.isDirectory) return ToolResult.Failure("Not a directory: $path")

    val include = args.optString("include", "").ifBlank { null }
    val max = args.optInt("max", 100).coerceIn(1, 1000)

    val matches = mutableListOf<String>()
    dir.walkTopDown()
      .onEnter { it.isDirectory && !isSkipped(it) }
      .filter { it.isFile && it.length() < MAX_FILE_BYTES && !looksBinary(it) }
      .forEach { file ->
        if (matches.size >= max) return@forEach
        val relative = context.relative(file)
        if (include != null && !Glob.matches(include, relative)) return@forEach

        val lines = runCatching { file.readLines() }.getOrNull() ?: return@forEach
        for ((index, line) in lines.withIndex()) {
          if (matches.size >= max) break
          if (regex.containsMatchIn(line)) {
            matches.add("$relative:${index + 1}: ${line.trim().take(300)}")
          }
        }
      }

    if (matches.isEmpty()) return ToolResult.Ok("No matches for /$pattern/")
    val sb = StringBuilder()
    matches.forEach { sb.append(it).append('\n') }
    if (matches.size >= max) sb.append("... truncated at $max matches")
    return ToolResult.Ok(sb.toString())
  }
}

private const val MAX_FILE_BYTES = 1_000_000L

private fun isSkipped(dir: File): Boolean =
  dir.name == "build" || dir.name == ".gradle" || dir.name == ".git" || dir.name == ".idea"

private val BINARY_EXTENSIONS =
  setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz",
    "jar", "aar", "zip", "gz", "tar", "apk", "dex", "class", "so", "dylib", "dll",
    "ttf", "otf", "woff", "woff2", "mp3", "mp4", "wav", "ogg", "db", "keystore", "jks",
  )

private fun looksBinary(file: File): Boolean = BINARY_EXTENSIONS.contains(file.extension.lowercase())

/** Minimal glob matcher supporting star, question mark and recursive double-star patterns. */
object Glob {

  fun matches(glob: String, path: String): Boolean =
    regex(glob.replace('\\', '/')).matches(path.replace('\\', '/'))

  private val cache = HashMap<String, Regex>()

  private fun regex(glob: String): Regex =
    cache.getOrPut(glob) {
      val sb = StringBuilder("^")
      var i = 0
      while (i < glob.length) {
        val c = glob[i]
        when (c) {
          '*' ->
            if (i + 1 < glob.length && glob[i + 1] == '*') {
              if (i + 2 < glob.length && glob[i + 2] == '/') {
                sb.append("(?:.*/)?")
                i += 2
              } else {
                sb.append(".*")
                i++
              }
            } else {
              sb.append("[^/]*")
            }
          '?' -> sb.append("[^/]")
          '/', '-' -> sb.append(c)
          else -> sb.append(Regex.escape(c.toString()))
        }
        i++
      }
      sb.append('$')
      Regex(sb.toString())
    }
}
