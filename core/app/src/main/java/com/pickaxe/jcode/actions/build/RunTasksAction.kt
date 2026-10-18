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
import com.pickaxe.jcode.projects.internal.ProjectManagerImpl
import com.pickaxe.jcode.fragments.RunTasksDialogFragment
import com.pickaxe.jcode.resources.R

 /** @author Akash Yadav */
class RunTasksAction(context: Context, override val order: Int) : BaseBuildAction() {
  override val id: String = "ide.editor.build.runTasks"
  private var dialog: RunTasksDialogFragment? = null

  init {
    label = context.getString(R.string.title_run_tasks)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run_tasks)
  }

  override suspend fun execAction(data: ActionData): Any {
    dialog?.dismiss()
    dialog = null
    dialog = RunTasksDialogFragment()
    return dialog!!
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result !is RunTasksDialogFragment) {
      return
    }
    val activity = data.getActivity()!!
    result.show(activity.supportFragmentManager, this.id)
  }

  override fun destroy() {
    super.destroy()
    try {
      dialog?.dismiss()
    } catch (e: Exception) {
      // ignored
    }
    dialog = null
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    // Hide for non-Gradle projects
    val projectDir = ProjectManagerImpl.getInstance().projectDir
    val buildSystem = BuildSystemDetector.detect(projectDir)
    if (buildSystem != null && buildSystem != BuildSystem.GRADLE) {
      visible = false
      enabled = false
    }
  }
}