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

package com.pickaxe.jcode.artificial.tools.hashline

import java.io.File

/**
 * The bits of the surrounding app a patch needs: how to turn a path from the model into a file,
 * whether that file is in bounds, and how to show it back.
 *
 * Keeping the patcher behind this seam means it never touches the file writer, the undo log or
 * events, so it stays pure enough to reason about and test on its own.
 */
interface PatchWorkspace {

  /** Absolute file for a path from the model. */
  fun resolve(path: String): File

  /** True when the file is inside the project. */
  fun withinProject(file: File): Boolean

  /** Path to print back to the model. */
  fun display(file: File): String
}
