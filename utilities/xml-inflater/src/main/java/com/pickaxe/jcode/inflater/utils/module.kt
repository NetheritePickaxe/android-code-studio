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

package com.pickaxe.jcode.inflater.utils

import com.pickaxe.jcode.projects.IProjectManager
import com.pickaxe.jcode.projects.ModuleProject
import java.io.File

private var currentModule: com.pickaxe.jcode.projects.ModuleProject? = null
var isParsing: Boolean = false
  private set

val module: com.pickaxe.jcode.projects.ModuleProject
  get() =
      currentModule ?: throw IllegalStateException("You must call startParse(com.pickaxe.jcode.projects.ModuleProject) first")

fun startParse(file: File) {
  if (isParsing) {
    return
  }
  (IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false))
      ?.let { startParse(it) }
}

fun startParse(m: com.pickaxe.jcode.projects.ModuleProject) {
  currentModule = m
  isParsing = true
}

fun endParse() {
  currentModule = null
  isParsing = false
}
