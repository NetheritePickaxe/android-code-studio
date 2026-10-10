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
import com.pickaxe.jcode.resources.R
import com.pickaxe.jcode.utils.flashError

/**
 * Runs the current project as a Java application via the Gradle `application`
 * plugin's `run` task (i.e. `gradle run`).
 *
 * This is the primary "Run" action for jcode: it builds and launches the main
 * class declared by the `application` plugin in the root project's build
 * script. Build output is streamed to the build output sheet exactly like
 * [RunTasksAction]. For multi-module projects where the application lives in a
 * submodule, use the "Run tasks" action to execute `:<module>:run` instead.
 */
class RunJavaAction(context: Context, override val order: Int) : BaseBuildAction() {

  override val id: String = "ide.editor.build.runJava"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.title_run_java)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run)
  }

  override suspend fun execAction(data: ActionData): Any {
    val buildService = buildService ?: run {
      flashError(R.string.msg_tooling_server_unavailable)
      return ""
    }
    if (!buildService.isToolingServerStarted()) {
      flashError(R.string.msg_tooling_server_unavailable)
      return ""
    }
    // `gradle run` — the application plugin's run task on the root project.
    // executeTasks(...) returns immediately; the actual build runs on the
    // tooling server and its output is streamed to the build output sheet.
    buildService.executeTasks("run")
    return ""
  }
}
