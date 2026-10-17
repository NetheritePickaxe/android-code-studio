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

import com.pickaxe.jcode.artificial.harness.Tool
import com.pickaxe.jcode.artificial.harness.ToolContext
import com.pickaxe.jcode.artificial.harness.ToolResult
import com.pickaxe.jcode.artificial.skills.SkillRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Loads a specialized skill's instructions into the conversation context. */
class SkillTool(private val context: ToolContext) : Tool {

  override val name = "skill"

  override val description: String
    get() =
      "Load a specialized skill's instructions and resources when the task matches its " +
        "description. The skill ID must match an available skill. " +
        "Available skills: " + SkillRegistry.all().joinToString(", ") { "${it.id} (${it.description})" } + ". " +
        "Pass id=\"list\" to get just the IDs and descriptions."

  override fun parameters(): JSONObject {
    val props = JSONObject()
    props.put("id", stringProp("The ID of an available skill to load."))
    return objectSchema(props, JSONArray().put("id"))
  }

  override suspend fun execute(args: JSONObject): ToolResult {
    val id = args.optString("id", "").trim()
    if (id.isBlank()) return ToolResult.Failure("id is required")

    if (id.equals("list", ignoreCase = true)) {
      val listing = SkillRegistry.all()
        .joinToString("\n") { "- ${it.id}: ${it.description}" }
      return ToolResult.Ok("Available skills:\n$listing")
    }

    val skill = SkillRegistry.find(id)
      ?: return ToolResult.Failure("Skill not found: $id. Try id=\"list\" to see available skills.")

    val output = buildString {
      appendLine("<skill_content id=\"${skill.id}\" name=\"${skill.name}\">")
      if (skill.content.isNotBlank()) {
        append(skill.content.trimEnd())
        if (!skill.content.endsWith("\n")) append('\n')
      } else {
        appendLine(skill.description)
      }
      appendLine("</skill_content>")
    }
    return ToolResult.Ok(output)
  }
}
