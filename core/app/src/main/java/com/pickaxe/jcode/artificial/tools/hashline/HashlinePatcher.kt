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

import com.pickaxe.jcode.artificial.harness.EditRegisters
import java.io.File

/**
 * Checks a parsed patch against the files on disk and computes the text each file ends up with.
 *
 * Every line number is the one the model saw in `read_file`, so all ops of a section are built
 * from the same original lines and are never shifted by an earlier op. Nothing here writes: the
 * caller owns the file writer, the undo log and the events.
 */
object HashlinePatcher {

  /** One file's planned change. [updated] is null when the file is removed. */
  data class Plan(
    val file: File,
    val display: String,
    val original: String,
    val updated: String?,
    val moveTo: File?,
    val moveDisplay: String?,
  )

  fun plan(
    sections: List<FileSection>,
    workspace: PatchWorkspace,
    registers: EditRegisters,
  ): List<Plan> {
    val groups = LinkedHashMap<String, MutableList<FileSection>>()
    val files = LinkedHashMap<String, File>()
    for (section in sections) {
      val file = workspace.resolve(section.path)
      if (!workspace.withinProject(file)) {
        throw HashlineException("refusing to edit outside the project: ${section.path}")
      }
      val key = workspace.display(file)
      groups.getOrPut(key) { mutableListOf() }.add(section)
      files[key] = file
    }
    val plans = mutableListOf<Plan>()
    for ((key, group) in groups) {
      val file = files.getValue(key)
      plans.add(applyGroup(file, group, workspace, registers))
    }
    return plans
  }

  private fun applyGroup(
    file: File,
    group: List<FileSection>,
    workspace: PatchWorkspace,
    registers: EditRegisters,
  ): Plan {
    if (!file.isFile) {
      throw HashlineException(
        "file not found: ${workspace.display(file)} (create it with write_file)"
      )
    }
    val display = workspace.display(file)
    val declared = group.firstNotNullOfOrNull { it.tag }
      ?: throw HashlineException(
        "missing tag on [$display]: copy the header from read_file, as in [$display#A1B2]"
      )
    if (group.any { it.tag != null && it.tag != declared }) {
      throw HashlineException("section [$display] was given two different tags")
    }

    val original = file.readText()
    val actual = FileTag.of(original)
    if (declared != actual) {
      throw HashlineException(
        "[$display#$declared] is stale: $display is now #$actual. Re-read $display and copy the fresh tag."
      )
    }

    val hunks = group.flatMap { it.hunks }
    val removes = hunks.filterIsInstance<Rem>()
    if (removes.isNotEmpty()) {
      if (hunks.size > 1) {
        throw HashlineException("line ${removes.first().sourceLine}: REM must be the only op for $display")
      }
      return Plan(file, display, original, null, null, null)
    }

    val source = Source(original)
    val lines = source.lines
    val ranges = mutableListOf<RangeEdit>()
    val gaps = LinkedHashMap<Int, MutableList<List<String>>>()

    for (hunk in hunks) {
      when (hunk) {
        is Rem -> {}
        is Mv -> {}
        is PutBody -> {
          when (val locator = hunk.locator) {
            is GapLocator -> insert(gaps, gapIndex(locator, lines, hunk.sourceLine), hunk.body)
            is RangeLocator -> ranges.add(range(lines, locator, hunk.body, hunk.sourceLine))
            is BlockLocator -> ranges.add(blockRange(lines, locator, hunk.body, hunk.sourceLine))
          }
        }
        is PutRegister -> {
          val held = registers.recall(hunk.name)
            ?: throw HashlineException(
              "line ${hunk.sourceLine}: " +
                (if (hunk.name == null) "nothing has been cut to paste"
                else "register @${hunk.name} is empty") +
                " — CUT the lines first"
            )
          when (val locator = hunk.locator) {
            is GapLocator -> insert(gaps, gapIndex(locator, lines, hunk.sourceLine), held)
            is RangeLocator -> ranges.add(range(lines, locator, held, hunk.sourceLine))
            is BlockLocator -> ranges.add(blockRange(lines, locator, held, hunk.sourceLine))
          }
        }
        is Cut -> {
          val locator = hunk.locator
          val start: Int
          val end: Int
          when (locator) {
            is RangeLocator -> {
              start = locator.start
              end = locator.end
            }
            is BlockLocator -> {
              val span = blockBounds(lines, locator, hunk.sourceLine)
              start = span.first
              end = span.second
            }
            is GapLocator -> throw HashlineException(
              "line ${hunk.sourceLine}: CUT needs a range (CUT 3.=5) or a block (CUT 3*)"
            )
          }
          bounds(lines, start, end, hunk.sourceLine, display)
          registers.store(hunk.name, lines.subList(start - 1, end).toList())
          ranges.add(RangeEdit(start, end, emptyList()))
        }
      }
    }

    ranges.sortBy { it.start }
    for (index in 0 until ranges.size - 1) {
      if (ranges[index + 1].start <= ranges[index].end) {
        throw HashlineException("$display: edits overlap at line ${ranges[index + 1].start}")
      }
    }

    val byStart = ranges.associateBy { it.start }
    val output = mutableListOf<String>()
    var line = 1
    while (line <= lines.size) {
      gaps[line - 1]?.forEach { output.addAll(it) }
      val edit = byStart[line]
      if (edit != null) {
        output.addAll(edit.replacement)
        line = edit.end + 1
      } else {
        output.add(lines[line - 1])
        line++
      }
    }
    gaps[lines.size]?.forEach { output.addAll(it) }

    val moves = hunks.filterIsInstance<Mv>()
    if (moves.size > 1) {
      throw HashlineException("$display: only one MV is allowed per section")
    }
    var moveTo: File? = null
    var moveDisplay: String? = null
    val move = moves.firstOrNull()
    if (move != null) {
      moveTo = workspace.resolve(move.destination)
      if (!workspace.withinProject(moveTo)) {
        throw HashlineException("line ${move.sourceLine}: refusing to move outside the project: ${move.destination}")
      }
      moveDisplay = workspace.display(moveTo)
    }

    val changed = ranges.isNotEmpty() || gaps.isNotEmpty()
    return Plan(
      file = file,
      display = display,
      original = original,
      updated = if (changed) source.render(output) else original,
      moveTo = moveTo,
      moveDisplay = moveDisplay,
    )
  }

  private class RangeEdit(val start: Int, val end: Int, val replacement: List<String>)

  /** Logical lines of a file, with its line ending style kept for the round trip. */
  private class Source(val text: String) {
    private val crlf = text.contains("\r\n")
    private val trailingNewline: Boolean
    val lines: List<String>

    init {
      val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
      trailingNewline = normalized.isNotEmpty() && normalized.endsWith("\n")
      val body = if (trailingNewline) normalized.dropLast(1) else normalized
      lines = if (body.isEmpty()) emptyList() else body.split("\n")
    }

    fun render(newLines: List<String>): String {
      val joined = newLines.joinToString("\n") + if (trailingNewline) "\n" else ""
      return if (crlf) joined.replace("\n", "\r\n") else joined
    }
  }

  private fun range(
    lines: List<String>,
    locator: RangeLocator,
    replacement: List<String>,
    sourceLine: Int,
  ): RangeEdit {
    bounds(lines, locator.start, locator.end, sourceLine, null)
    return RangeEdit(locator.start, locator.end, replacement)
  }

  private fun blockRange(lines: List<String>, locator: BlockLocator, replacement: List<String>, sourceLine: Int): RangeEdit {
    val bounds = blockBounds(lines, locator, sourceLine)
    return RangeEdit(bounds.first, bounds.second, replacement)
  }

  private fun blockBounds(lines: List<String>, locator: BlockLocator, sourceLine: Int): Pair<Int, Int> {
    val end = blockEnd(lines, locator.line)
      ?: throw HashlineException(
        "line $sourceLine: line ${locator.line} does not open a block; use N.=N for a single line"
      )
    return locator.line to end
  }

  private fun insert(gaps: MutableMap<Int, MutableList<List<String>>>, index: Int, body: List<String>) {
    gaps.getOrPut(index) { mutableListOf() }.add(body)
  }

  private fun gapIndex(locator: GapLocator, lines: List<String>, sourceLine: Int): Int {
    val count = lines.size
    return when (locator.kind) {
      GapKind.BEFORE -> {
        if (locator.line > lines.size + 1) {
          throw HashlineException("line $sourceLine: line ${locator.line} is past the end of the file")
        }
        locator.line - 1
      }
      GapKind.AFTER -> {
        if (locator.line > lines.size) {
          throw HashlineException("line $sourceLine: line ${locator.line} is past the end of the file")
        }
        locator.line
      }
      GapKind.AFTER_BLOCK -> blockEnd(lines, locator.line)
        ?: throw HashlineException(
          "line $sourceLine: line ${locator.line} does not open a block"
        )
      GapKind.TAIL -> count
    }
  }

  private fun bounds(
    lines: List<String>,
    start: Int,
    end: Int,
    sourceLine: Int,
    display: String?,
  ) {
    if (start < 1 || end < start || end > lines.size) {
      val scope = if (display == null) "the file" else display
      throw HashlineException(
        "line $sourceLine: lines $start..$end are outside $scope (${lines.size} lines)"
      )
    }
  }

  /**
   * Last line of the block opening at [anchor].
   *
   * A line that opens a brace is closed by the matching brace. Otherwise the block is the run of
   * deeper indented lines that follows, which covers Python, YAML and the braceless Kotlin forms.
   * Returns null when neither reading finds a block, so `*` fails loudly rather than guessing.
   */
  private fun blockEnd(lines: List<String>, anchor: Int): Int? {
    if (anchor < 1 || anchor > lines.size) return null
    val opening = lines[anchor - 1]
    val depth = braceDelta(opening)
    if (depth > 0) {
      var current = depth
      for (line in anchor + 1..lines.size) {
        current += braceDelta(lines[line - 1])
        if (current <= 0) return line
      }
      return null
    }
    val indent = indentOf(opening)
    var end = anchor
    for (line in anchor + 1..lines.size) {
      val candidate = lines[line - 1]
      if (candidate.isBlank()) continue
      if (indentOf(candidate) > indent) end = line else break
    }
    return if (end > anchor) end else null
  }

  private fun braceDelta(line: String): Int =
    line.count { it == '{' } - line.count { it == '}' }

  private fun indentOf(line: String): Int {
    var count = 0
    for (char in line) {
      if (char == ' ') count++ else if (char == '\t') count += 4 else break
    }
    return count
  }
}
