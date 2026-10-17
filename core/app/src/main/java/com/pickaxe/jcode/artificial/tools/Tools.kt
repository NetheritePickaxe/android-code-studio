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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.artificial.tools

import com.pickaxe.jcode.artificial.harness.Tool
import com.pickaxe.jcode.artificial.harness.ToolContext

/** The default IDE tool set exposed to the agent. */
fun defaultTools(context: ToolContext): List<Tool> =
  buildList {
    add(SkillTool(context))
    add(SubagentTool(context))
    add(ReadFileTool(context))
    add(WriteFileTool(context))
    add(EditFileTool(context))
    add(ListFilesTool(context))
    add(GrepTool(context))
    if (context.commands != null) {
      add(RunCommandTool(context))
    }
  }
