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
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.treesitter.api

import com.pickaxe.jcode.treesitter.TSQueryCapture
import com.pickaxe.jcode.treesitter.TSQueryMatch
import com.pickaxe.jcode.utils.DefaultRecyclable
import com.pickaxe.jcode.utils.RecyclableObjectPool

/** @author Akash Yadav */
class TreeSitterQueryMatch
@JvmOverloads
internal constructor(
    id: Int = 0,
    patternIndex: Int = 0,
    captures: Array<out TSQueryCapture?>? = null,
) :
    TSQueryMatch(id, patternIndex, captures, null),
    RecyclableObjectPool.Recyclable by DefaultRecyclable() {

  companion object {

    @JvmStatic
    fun obtain(
        id: Int,
        patternIndex: Int,
        captures: Array<out TSQueryCapture?>?,
    ): TreeSitterQueryMatch {
      return obtainFromPool<TreeSitterQueryMatch>().apply {
        this.id = id
        this.patternIndex = patternIndex
        this.captures = captures
      }
    }
  }

  override fun recycle() {
    this.id = 0
    this.patternIndex = 0
    this.captures = null
    returnToPool()
  }
}
