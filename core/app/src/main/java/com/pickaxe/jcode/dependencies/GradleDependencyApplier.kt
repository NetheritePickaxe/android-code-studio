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
package com.pickaxe.jcode.dependencies

import java.io.File

/**
 * Writes the dependencies configured in the settings into a Gradle project's build script.
 *
 * The dependencies are placed in a delimited managed block, so applying the settings again
 * replaces the previous block instead of appending duplicates:
 *
 * ```
 * // >>> jcode managed dependencies >>>
 * repositories { ... }
 * dependencies { ... }
 * // <<< jcode managed dependencies <<<
 * ```
 */
object GradleDependencyApplier {

  private const val BEGIN = "// >>> jcode managed dependencies >>>"
  private const val END = "// <<< jcode managed dependencies <<<"

  /** Configurations that a dependency entry can be prefixed with. */
  private val CONFIGURATIONS =
      setOf(
          "implementation",
          "api",
          "compileOnly",
          "runtimeOnly",
          "testImplementation",
          "annotationProcessor",
      )

  /** The result of applying the dependencies. */
  data class Result(val script: File?, val error: String?) {
    val success: Boolean
      get() = error == null
  }

  /** Returns the root build script of [projectDir], or `null` if there is none. */
  fun findBuildScript(projectDir: File): File? {
    File(projectDir, "build.gradle.kts").takeIf { it.isFile }?.let { return it }
    File(projectDir, "build.gradle").takeIf { it.isFile }?.let { return it }
    return null
  }

  /**
   * Applies [repositories] and [dependencies] to the build script of [projectDir].
   *
   * Passing empty lists removes a previously written managed block.
   */
  fun apply(
      projectDir: File,
      repositories: List<String>,
      dependencies: List<String>,
  ): Result {
    val script = findBuildScript(projectDir) ?: return Result(null, "no_build_script")

    val original =
        try {
          script.readText(Charsets.UTF_8)
        } catch (e: Exception) {
          return Result(script, e.message ?: "read_failed")
        }

    val cleaned = removeManagedBlock(original).trimEnd()
    val block = buildBlock(script.name.endsWith(".kts"), repositories, dependencies)
    val updated = if (block.isEmpty()) "$cleaned\n" else "$cleaned\n\n$block\n"

    if (updated == original) return Result(script, null)

    return try {
      script.writeText(updated, Charsets.UTF_8)
      Result(script, null)
    } catch (e: Exception) {
      Result(script, e.message ?: "write_failed")
    }
  }

  /** Removes the managed block from [text], including the surrounding blank lines. */
  internal fun removeManagedBlock(text: String): String {
    val begin = text.indexOf(BEGIN)
    if (begin < 0) return text
    val end = text.indexOf(END, begin)
    if (end < 0) return text
    val endOfLine = text.indexOf('\n', end).let { if (it < 0) text.length else it + 1 }
    val startOfLine = text.lastIndexOf('\n', begin).let { if (it < 0) 0 else it + 1 }
    return text.substring(0, startOfLine) + text.substring(endOfLine)
  }

  /** Builds the managed block for the given DSL. */
  internal fun buildBlock(
      isKts: Boolean,
      repositories: List<String>,
      dependencies: List<String>,
  ): String {
    if (repositories.isEmpty() && dependencies.isEmpty()) return ""

    val builder = StringBuilder()
    builder.append(BEGIN).append('\n')

    if (repositories.isNotEmpty()) {
      builder.append("repositories {\n")
      repositories.forEach { repository ->
        if (isKts) {
          builder.append("    maven { url = uri(\"").append(repository).append("\") }\n")
        } else {
          builder.append("    maven { url \"").append(repository).append("\" }\n")
        }
      }
      builder.append("}\n")
    }

    if (dependencies.isNotEmpty()) {
      builder.append("dependencies {\n")
      dependencies.forEach { entry ->
        val (configuration, coordinates) = splitConfiguration(entry)
        if (isKts) {
          builder.append("    ").append(configuration).append("(\"").append(coordinates)
              .append("\")\n")
        } else {
          builder.append("    ").append(configuration).append(" '").append(coordinates)
              .append("'\n")
        }
      }
      builder.append("}\n")
    }

    builder.append(END)
    return builder.toString()
  }

  /**
   * Splits `implementation group:artifact:version` into the configuration and the coordinates.
   * Entries without a known configuration prefix default to `implementation`.
   */
  internal fun splitConfiguration(entry: String): Pair<String, String> {
    val trimmed = entry.trim()
    val space = trimmed.indexOf(' ')
    if (space > 0) {
      val candidate = trimmed.substring(0, space)
      if (candidate in CONFIGURATIONS) {
        return candidate to trimmed.substring(space + 1).trim()
      }
    }
    return "implementation" to trimmed
  }
}
