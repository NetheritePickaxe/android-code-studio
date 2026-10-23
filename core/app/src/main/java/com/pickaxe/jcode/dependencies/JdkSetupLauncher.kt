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
package com.pickaxe.jcode.dependencies

import android.app.Activity
import android.content.Intent
import com.pickaxe.jcode.activities.TerminalActivity
import com.pickaxe.jcode.utils.Environment
import java.io.File

/**
 * Detects whether a JDK is available and launches the `idesetup` terminal session that installs it.
 *
 * The JDK is **not** bundled with the app: it is installed into the app's Termux prefix by the
 * `idesetup` script (which runs `apt install openjdk-17` and the Gradle distribution). This helper
 * is the single entry point used by the first-run prompt and the settings row.
 */
object JdkSetupLauncher {

  /** Arguments passed to the `idesetup` script: non-interactive, continue on cache, no warnings. */
  val IDESETUP_ARGS = arrayOf("-y", "-c", "-wn")

  /** Returns `true` when both `javac` and `java` can be executed from the configured JAVA_HOME. */
  fun isJdkAvailable(): Boolean {
    val home = Environment.JAVA_HOME ?: return false
    val javac = File(home, "bin/javac")
    val java = Environment.JAVA ?: return false
    return javac.exists() && java.exists()
  }

  /** Opens the terminal and runs the `idesetup` script with [IDESETUP_ARGS]. */
  fun launch(activity: Activity) {
    // The Termux prefix binaries may have lost their executable permission
    // during bootstrap extraction (especially on Android 12+ where execute permission
    // is not preserved by default). Fix them before launching the terminal so that
    // bash, pkg, apt, and the idesetup script itself can actually run.
    fixExecutablePermissions()

    val intent =
        Intent(activity, TerminalActivity::class.java).apply {
          putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP, true)
          putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP_ARGS, IDESETUP_ARGS)
        }
    activity.startActivity(intent)
  }

  /**
   * Sets the executable permission on every file in the Termux prefix's bin directory.
   *
   * Without this, the idesetup script fails with "Permission denied: .../usr/bin/bash"
   * because bash (and pkg, apt, etc.) cannot be executed.
   */
  private fun fixExecutablePermissions() {
    val binDir = Environment.BIN_DIR ?: return
    if (!binDir.isDirectory) return

    binDir.listFiles()?.forEach { file ->
      if (file.isFile) {
        Environment.setExecutable(file)
      }
    }

    // Also fix the login shell and bash specifically
    Environment.BASH_SHELL?.let { Environment.setExecutable(it) }
    Environment.LOGIN_SHELL?.let { Environment.setExecutable(it) }
  }
}
