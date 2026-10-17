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
 * Provider-independent ledger of the files the agent has changed.
 *
 * Tools append an entry for every write, so the UI can undo the last change or show history
 * without depending on whichever provider ran the task.
 */
class ModificationLog {

  data class Entry(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
  )

  private val entries = mutableListOf<Entry>()

  fun record(filePath: String, previousContent: String?, newContent: String) {
    entries.add(Entry(System.currentTimeMillis(), filePath, previousContent, newContent))
  }

  /** Remove and return the most recent entry, or null when there is nothing to undo. */
  fun removeLast(): Entry? = if (entries.isEmpty()) null else entries.removeAt(entries.size - 1)

  fun all(): List<Entry> = entries.toList()

  fun clear() = entries.clear()
}
