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

/**
 * Turns hashline patch text into [FileSection]s.
 *
 * The grammar is line oriented:
 * ```
 * [path#TAG]
 * PUT 3.=7:
 * +first
 * +second
 * CUT 9*
 * ```
 * `*** Begin Patch` / `*** End Patch` are accepted and ignored, as is the
 * `*** Update File:` header noise other tools prepend to a path.
 */
object HashlineParser {

  fun parse(input: String): List<FileSection> {
    val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return Parser(lines).parse()
  }

  private val BEGIN = Regex("""^\*{3}\s*Begin Patch$""", RegexOption.IGNORE_CASE)
  private val END = Regex("""^\*{3}\s*End Patch$""", RegexOption.IGNORE_CASE)
  private val HEADER = Regex("""^\[(.+)]$""")
  private val NOISE = Regex("""^\*{3}\s*(?:Add|Update|Delete)\s+File\s*:\s*|^\*{3}\s*Move\s+to\s*:\s*""")
  private val TAG_SUFFIX = Regex("""#([0-9a-fA-F]{4})$""")
  private val REGISTER = Regex("""\s@([A-Za-z0-9_-]+)$""")
  private val READ_PREFIX = Regex("""^\s*(?:(?:>>>|>>)\s*)?(?:[+*-]\s*)?\d+[:|]""")

  private class Parser(private val lines: List<String>) {

    private val sections = mutableListOf<FileSection>()
    private var path: String? = null
    private var tag: String? = null
    private val hunks = mutableListOf<Hunk>()

    fun parse(): List<FileSection> {
      var index = 0
      while (index < lines.size) {
        val raw = lines[index]
        val lineNo = index + 1
        val line = raw.trim()
        index++
        when {
          line.isEmpty() -> {}
          BEGIN.matches(line) || END.matches(line) -> {}
          HEADER.matches(line) -> {
            flush()
            val (name, hash) = header(line)
            path = name
            tag = hash
          }
          line.startsWith("PUT ") -> index = put(line, lineNo, index)
          line.startsWith("CUT ") -> cut(line, lineNo)
          line == "REM" -> hunks.add(Rem(lineNo))
          line.startsWith("MV ") -> hunks.add(Mv(lineNo, unquote(line.removePrefix("MV ").trim())))
          else -> throw HashlineException("line $lineNo: cannot parse \"$line\"")
        }
      }
      flush()
      return sections
    }

    /** Close the section being built, if any. */
    private fun flush() {
      val name = path ?: return
      if (hunks.isEmpty()) throw HashlineException("section [$name] has no ops")
      sections.add(FileSection(name, tag, hunks.toList()))
      path = null
      tag = null
      hunks.clear()
    }

    private fun header(line: String): Pair<String, String?> {
      val inner = HEADER.matchEntire(line)?.groupValues?.get(1)?.trim()
        ?: throw HashlineException("cannot parse header $line")
      val cleaned = NOISE.replace(inner, "").trim()
      if (cleaned.isEmpty()) throw HashlineException("header $line has no path")
      val match = TAG_SUFFIX.find(cleaned)
      return if (match == null) {
        cleaned to null
      } else {
        cleaned.substring(0, match.range.first).trim() to match.groupValues[1].uppercase()
      }
    }

    private fun put(line: String, lineNo: Int, next: Int): Int {
      var rest = line.removePrefix("PUT ").trim()
      val hasBody = rest.endsWith(":")
      if (hasBody) rest = rest.dropLast(1).trim()
      if (rest.isEmpty()) throw HashlineException("line $lineNo: PUT needs a locator")
      var name: String? = null
      if (!hasBody) {
        val match = REGISTER.find(rest)
        if (match != null) {
          name = match.groupValues[1]
          rest = rest.substring(0, match.range.first).trim()
        }
      }
      val locator = locator(rest, lineNo)
      return when {
        hasBody -> readBody(next, lineNo, locator)
        name != null -> {
          hunks.add(PutRegister(lineNo, locator, name))
          next
        }
        locator is GapLocator -> {
          hunks.add(PutRegister(lineNo, locator, null))
          next
        }
        else -> throw HashlineException(
          "line $lineNo: PUT needs a body (PUT $rest:) or a register (PUT $rest @name)"
        )
      }
    }

    private fun readBody(from: Int, lineNo: Int, locator: Locator): Int {
      val rows = mutableListOf<String>()
      var index = from
      while (index < lines.size && lines[index].startsWith("+")) {
        rows.add(lines[index].substring(1))
        index++
      }
      if (rows.isEmpty()) throw HashlineException("line $lineNo: PUT …: needs at least one + body line")
      hunks.add(PutBody(lineNo, locator, stripReadPrefixes(rows)))
      return index
    }

    private fun cut(line: String, lineNo: Int) {
      var rest = line.removePrefix("CUT ").trim()
      var name: String? = null
      val match = REGISTER.find(rest)
      if (match != null) {
        name = match.groupValues[1]
        rest = rest.substring(0, match.range.first).trim()
      }
      val locator = locator(rest, lineNo)
      if (locator is GapLocator) {
        throw HashlineException("line $lineNo: CUT needs a range (CUT 3.=5) or a block (CUT 3*)")
      }
      hunks.add(Cut(lineNo, locator, name))
    }

    private fun locator(text: String, lineNo: Int): Locator {
      if (text.isEmpty()) throw HashlineException("line $lineNo: missing locator")
      if (text.startsWith("<")) return GapLocator(GapKind.BEFORE, lineNumber(text.substring(1), lineNo))
      if (text.startsWith(">")) {
        val rest = text.substring(1)
        if (rest == "$") return GapLocator(GapKind.TAIL, 0)
        return if (rest.endsWith("*")) {
          GapLocator(GapKind.AFTER_BLOCK, lineNumber(rest.dropLast(1), lineNo))
        } else {
          GapLocator(GapKind.AFTER, lineNumber(rest, lineNo))
        }
      }
      if (text.endsWith("*")) return BlockLocator(lineNumber(text.dropLast(1), lineNo))
      val parts = text.split(".=")
      if (parts.size != 2) throw HashlineException("line $lineNo: bad locator \"$text\"")
      val start = lineNumber(parts[0], lineNo)
      val end = lineNumber(parts[1], lineNo)
      if (end < start) throw HashlineException("line $lineNo: range $start.$=$end ends before it starts")
      return RangeLocator(start, end)
    }

    private fun lineNumber(text: String, lineNo: Int): Int {
      val value = text.trim()
      if (value.isEmpty() || !value.all { it in '0'..'9' } || value.startsWith("0")) {
        throw HashlineException("line $lineNo: \"$text\" is not a line number")
      }
      return value.toIntOrNull() ?: throw HashlineException("line $lineNo: \"$text\" is not a line number")
    }

    /**
     * Drop the `12:` gutter a model may copy from `read_file` output.
     *
     * Only applied when every non-empty row carries one, so a body that merely happens to start
     * with digits is left alone.
     */
    private fun stripReadPrefixes(rows: List<String>): List<String> {
      val nonEmpty = rows.filter { it.isNotEmpty() }
      if (nonEmpty.isEmpty()) return rows
      if (!nonEmpty.all { READ_PREFIX.containsMatchIn(it) }) return rows
      return rows.map { READ_PREFIX.replace(it, "") }
    }

    private fun unquote(value: String): String {
      val quoted = value.length >= 2 &&
        ((value.startsWith("\"") && value.endsWith("\"")) ||
          (value.startsWith("'") && value.endsWith("'")))
      return if (quoted) value.substring(1, value.length - 1) else value
    }
  }
}
