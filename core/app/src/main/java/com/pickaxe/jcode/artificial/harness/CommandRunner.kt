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

import java.io.File

/** Outcome of a shell command. */
data class CommandResult(
  val exitCode: Int,
  val output: String,
  val timedOut: Boolean,
)

/**
 * Runs a shell command on behalf of the agent.
 *
 * The harness stays free of Android and Termux details; the app supplies an implementation that
 * runs commands inside the bundled toolchain.
 */
interface CommandRunner {
  /** Run [command] in [workingDir], killing it once [timeoutMillis] elapses. */
  suspend fun run(command: String, workingDir: File, timeoutMillis: Long): CommandResult
}
