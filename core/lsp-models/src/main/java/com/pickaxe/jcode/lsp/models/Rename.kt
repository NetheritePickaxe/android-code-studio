/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.lsp.models

import com.pickaxe.jcode.lsp.CancellableRequestParams
import com.pickaxe.jcode.models.Position
import com.pickaxe.jcode.progress.ICancelChecker
import java.nio.file.Path

/**
 * Parameters for a rename request. The symbol at [position] in [file] must be renamed to [newName]
 * in all the places where it is declared or referenced.
 */
data class RenameParams(
    var file: Path,
    var position: Position,
    var newName: String,
    override val cancelChecker: ICancelChecker,
) : CancellableRequestParams

/**
 * Result of a rename request. [changes] contains one entry per file that must be modified, each
 * carrying the list of edits to apply to that file.
 */
data class RenameResult(var changes: List<DocumentChange>)
