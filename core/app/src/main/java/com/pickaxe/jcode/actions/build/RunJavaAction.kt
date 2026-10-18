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

package com.pickaxe.jcode.actions.build

import android.content.Context
import androidx.core.content.ContextCompat
import com.pickaxe.jcode.actions.ActionData
import com.pickaxe.jcode.actions.BaseBuildAction
import com.pickaxe.jcode.projects.BuildSystem
import com.pickaxe.jcode.projects.BuildSystemDetector
import com.pickaxe.jcode.projects.builder.java.JavaBuildBackend
import com.pickaxe.jcode.projects.internal.ProjectManagerImpl
import com.pickaxe.jcode.resources.R
import com.pickaxe.jcode.utils.flashError
import com.pickaxe.jcode.utils.flashInfo
import org.slf4j.LoggerFactory

/**
 * Runs the current project as a Java application.
 *
 * For Gradle projects, this executes the `application` plugin's `run` task (i.e. `gradle run`).
 * Build output is streamed to the build output sheet via the Gradle Tooling API.
 *
 * For non-Gradle projects ([BuildSystem.JAVA_SINGLE] and [BuildSystem.JAVA_JAR]), this compiles
 * the Java sources directly with `javac` and runs the main class with `java`, without requiring
 * the Gradle Tooling API. Output is streamed to the build output sheet via [JavaBuildBackend].
 */
class RunJavaAction(context: Context, override val order: Int) : BaseBuildAction() {

  companion object {
    private val log = LoggerFactory.getLogger(RunJavaAction::class.java)
  }

  override val id: String = "ide.editor.build.runJava"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.title_run_java)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val context = data.getActivity()
    if (context == null) {
      visible = false
      return
    }
    visible = true

    // For non-Gradle projects, enable even if the Gradle build service is not available
    val projectDir = safeProjectDir()
    if (projectDir != null) {
      val buildSystem = BuildSystemDetector.detect(projectDir)
      if (buildSystem != null && buildSystem != BuildSystem.GRADLE) {
        enabled = true
        return
      }
    }

    enabled = buildService?.let { !it.isBuildInProgress } == true
  }

  override suspend fun execAction(data: ActionData): Any {
    val projectDir = safeProjectDir() ?: run {
      flashError("No project directory is set.")
      return ""
    }

    val buildSystem = BuildSystemDetector.detect(projectDir)
    log.debug("RunJavaAction: build system = {}", buildSystem)

    when (buildSystem) {
      BuildSystem.JAVA_SINGLE, BuildSystem.JAVA_JAR -> runJavaProject(data, projectDir)
      BuildSystem.GRADLE -> runGradleProject()
      null -> flashError("Could not determine the project type.")
    }
    return ""
  }

  /**
   * Runs a non-Gradle Java project using [JavaBuildBackend].
   * Output is streamed to the build output sheet.
   */
  private suspend fun runJavaProject(data: ActionData, projectDir: java.io.File) {
    val activity = data.getActivity() ?: run {
      flashError("Cannot access editor activity.")
      return
    }

    // Clear the build output and show the sheet
    activity.content.bottomSheet.clearBuildOutput()
    activity.appendBuildOutput("Building Java project (${projectDir.name})...")

    val backend = JavaBuildBackend { line ->
      activity.appendBuildOutput(line)
    }

    val result = backend.buildAndRun(projectDir)
    if (result.success) {
      activity.appendBuildOutput(result.message)
      flashInfo("Run completed.")
    } else {
      activity.appendBuildOutput("ERROR: ${result.message}")
      flashError(result.message)
    }
  }

  /**
   * Runs a Gradle project via the `application` plugin's `run` task.
   */
  private fun runGradleProject() {
    val buildService = buildService ?: run {
      flashError(R.string.msg_tooling_server_unavailable)
      return
    }
    if (!buildService.isToolingServerStarted()) {
      flashError(R.string.msg_tooling_server_unavailable)
      return
    }
    // `gradle run` — the application plugin's run task on the root project.
    // executeTasks(...) returns immediately; the actual build runs on the
    // tooling server and its output is streamed to the build output sheet.
    buildService.executeTasks("run")
  }

  /** Returns the current project directory, or null if not set. */
  private fun safeProjectDir(): java.io.File? {
    return try {
      ProjectManagerImpl.getInstance().projectDir
    } catch (e: Exception) {
      log.warn("Project directory not set", e)
      null
    }
  }
}
