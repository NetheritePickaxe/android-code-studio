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

/** Events emitted while the harness runs, for the UI to render. */
sealed class HarnessEvent {
  /** Assistant text produced during a turn. */
  data class AssistantText(val text: String) : HarnessEvent()

  /** A tool is about to run. */
  data class ToolStarted(val name: String, val summary: String) : HarnessEvent()

  /** A tool finished. */
  data class ToolFinished(val name: String, val ok: Boolean, val summary: String) : HarnessEvent()

  /** A project file was created or updated by a tool. */
  data class FileChanged(val path: String, val relativePath: String, val isNew: Boolean) : HarnessEvent()

  /** A progress line. */
  data class Status(val message: String) : HarnessEvent()

  /** A non-fatal failure worth surfacing. */
  data class Failure(val message: String) : HarnessEvent()
}
