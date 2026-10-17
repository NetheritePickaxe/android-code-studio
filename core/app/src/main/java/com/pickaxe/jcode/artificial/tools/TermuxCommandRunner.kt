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

package com.pickaxe.jcode.artificial.tools

import android.content.Context
import com.pickaxe.jcode.artificial.harness.CommandResult
import com.pickaxe.jcode.artificial.harness.CommandRunner
import com.pickaxe.jcode.utils.Environment
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs agent commands through the bundled Termux environment, so that gradle, git, java and the
 * other prefixed tools behave the same way they do in the integrated terminal.
 *
 * Output is drained on a background thread while the main thread waits with a timeout, which both
 * prevents the process from blocking on a full pipe and lets a hung command be killed.
 */
class TermuxCommandRunner(private val appContext: Context) : CommandRunner {

  private val shellEnvironment: Map<String, String> by lazy {
    runCatching { TermuxShellEnvironment().getEnvironment(appContext, false) }
      .getOrDefault(emptyMap())
  }

  override suspend fun run(
    command: String,
    workingDir: File,
    timeoutMillis: Long,
  ): CommandResult = withContext(Dispatchers.IO) {
    val shell = shellPath()
    val argv =
      if (shell.endsWith("bash")) listOf(shell, "-lc", command)
      else listOf(shell, "-c", command)

    val directory =
      when {
        workingDir.isDirectory -> workingDir
        Environment.HOME?.isDirectory == true -> Environment.HOME
        else -> null
      }

    val builder = ProcessBuilder(argv)
    builder.redirectErrorStream(true)
    if (directory != null) builder.directory(directory)
    if (shellEnvironment.isNotEmpty()) builder.environment().putAll(shellEnvironment)

    val process =
      try {
        builder.start()
      } catch (e: Exception) {
        return@withContext CommandResult(-1, "Failed to start command: ${e.message}", false)
      }

    val captured = StringBuilder()
    val lock = Any()
    val reader =
      thread(isDaemon = true, name = "ai-command-output") {
        try {
          process.inputStream.bufferedReader().use { stream ->
            val buffer = CharArray(8192)
            while (true) {
              val count = stream.read(buffer)
              if (count < 0) break
              synchronized(lock) {
                if (captured.length < MAX_CAPTURED_CHARS) {
                  captured.append(buffer, 0, count)
                }
              }
            }
          }
        } catch (_: Exception) {
          // The process died; whatever was captured so far is still useful.
        }
      }

    val finished =
      try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
      } catch (e: InterruptedException) {
        false
      }

    if (!finished) {
      process.destroyForcibly()
      runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }
    runCatching { reader.join(2_000) }

    val output = synchronized(lock) { captured.toString() }
    CommandResult(if (finished) process.exitValue() else -1, output, !finished)
  }

  private fun shellPath(): String =
    when {
      Environment.BASH_SHELL?.exists() == true -> Environment.BASH_SHELL.absolutePath
      Environment.LOGIN_SHELL?.exists() == true -> Environment.LOGIN_SHELL.absolutePath
      else -> "/system/bin/sh"
    }

  private companion object {
    const val MAX_CAPTURED_CHARS = 64_000
  }
}
