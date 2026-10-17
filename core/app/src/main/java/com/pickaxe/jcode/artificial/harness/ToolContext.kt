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

package com.pickaxe.jcode.artificial.harness

import com.pickaxe.jcode.artificial.file.AIFileWriter
import com.pickaxe.jcode.artificial.tools.hashline.PatchWorkspace
import java.io.File

/** Shared state handed to tools. */
class ToolContext(
  val projectRoot: File,
  val fileWriter: AIFileWriter,
  val modifications: ModificationLog = ModificationLog(),
  val commands: CommandRunner? = null,
  val registers: EditRegisters = EditRegisters(),
  private val onEvent: (HarnessEvent) -> Unit,
) : PatchWorkspace {
  fun emit(event: HarnessEvent) = onEvent(event)

  /** Resolve `path` against the project root when it is relative. */
  override fun resolve(path: String): File {
    val file = File(path)
    return if (file.isAbsolute) file else File(projectRoot, path)
  }

  /** Canonical path when possible, absolute otherwise. */
  fun canonical(file: File): String =
    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

  /** Canonical project root. */
  fun rootPath(): String = canonical(projectRoot)

  /** Path relative to the project root, for display. */
  override fun display(file: File): String {
    val root = rootPath()
    val path = canonical(file)
    if (path == root) return "."
    return if (path.startsWith("$root${File.separator}")) path.substring(root.length + 1) else path
  }

  /** True when `file` lives inside the project root. */
  override fun withinProject(file: File): Boolean {
    val root = rootPath()
    val path = canonical(file)
    return path == root || path.startsWith("$root${File.separator}")
  }
}
