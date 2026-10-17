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

import java.security.MessageDigest
import java.util.Locale

/** Raised for a malformed patch as well as for a stale anchor. */
class HashlineException(message: String) : Exception(message)

/**
 * The 4 hex digit tag a `[path#TAG]` section carries.
 *
 * The tag only ever travels between our own outputs: `read_file` prints it and a patch copies it
 * back, so the algorithm does not have to match another implementation. All it has to do is change
 * whenever the file changes, which turns "the model edited text it never saw" into a loud failure
 * instead of a silent corruption.
 */
object FileTag {

  fun of(content: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
    val value = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
    return String.format(Locale.ROOT, "%04X", value)
  }

  /** The `[path#TAG]` header for [content], as expected in a patch. */
  fun header(displayPath: String, content: String): String = "[$displayPath#${of(content)}]"
}

/** The line a hunk applies to. */
sealed interface Locator

/** `3.=7` — inclusive original lines. */
data class RangeLocator(val start: Int, val end: Int) : Locator

/** `3*` — the block opening at line 3. */
data class BlockLocator(val line: Int) : Locator

/** `<3`, `>3`, `>3*`, `>$`. */
data class GapLocator(val kind: GapKind, val line: Int) : Locator

enum class GapKind { BEFORE, AFTER, AFTER_BLOCK, TAIL }

/** One operation of a section. [sourceLine] is the patch line, used in diagnostics. */
sealed interface Hunk {
  val sourceLine: Int
}

/** `PUT <locator>:` with an inline `+` body. */
data class PutBody(override val sourceLine: Int, val locator: Locator, val body: List<String>) :
  Hunk

/** `PUT <locator> @name`, and the anonymous `PUT <gap>`: no inline body. */
data class PutRegister(override val sourceLine: Int, val locator: Locator, val name: String?) : Hunk

/** `CUT <locator> [@name]` — delete and capture. */
data class Cut(override val sourceLine: Int, val locator: Locator, val name: String?) : Hunk

/** `REM` — delete the file. */
data class Rem(override val sourceLine: Int) : Hunk

/** `MV <dest>` — rename the file after this section's edits. */
data class Mv(override val sourceLine: Int, val destination: String) : Hunk

/** One `[path#TAG]` section. [tag] is null when the header carried none. */
data class FileSection(val path: String, val tag: String?, val hunks: List<Hunk>)
