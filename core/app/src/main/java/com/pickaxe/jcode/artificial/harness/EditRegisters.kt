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

/**
 * Scratch space for cut lines.
 *
 * A `CUT` records the lines it removed and a later `PUT` pastes them, which is how a patch moves
 * a block between files. Named registers (`@fn`) survive the whole session so the paste can
 * happen in a later call; the anonymous register only holds the most recent unnamed cut.
 */
class EditRegisters {

  private val named = LinkedHashMap<String, List<String>>()

  private var anonymous: List<String>? = null

  /** Replace [name], or the anonymous slot when [name] is null. */
  fun store(name: String?, lines: List<String>) {
    if (name == null) anonymous = lines else named[name] = lines
  }

  /** Lines held by [name], or null when it has never been filled. */
  fun recall(name: String?): List<String>? = if (name == null) anonymous else named[name]

  fun clear() {
    named.clear()
    anonymous = null
  }
}
