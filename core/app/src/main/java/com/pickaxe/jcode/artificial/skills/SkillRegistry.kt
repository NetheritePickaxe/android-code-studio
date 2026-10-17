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

package com.pickaxe.jcode.artificial.skills

/** Built-in skills available to the agent. */
object SkillRegistry {

  private val skills = listOf(
    Skill(
      id = "frontend-design",
      name = "frontend-design",
      description = "Create distinctive, production-grade frontend interfaces with high design quality.",
      content = "<skill_content id=\"frontend-design\" name=\"frontend-design\">\nYou are an expert frontend designer and engineer focused on creating distinctive, production-grade interfaces.\n</skill_content>",
    ),
    Skill(
      id = "llm-wiki",
      name = "llm-wiki",
      description = "管理 Obsidian wiki 知识库。",
      content = "<skill_content id=\"llm-wiki\" name=\"llm-wiki\">\nManage and organize an Obsidian knowledge base.\n</skill_content>",
    ),
    Skill(
      id = "opencode",
      name = "OpenCode",
      description = "Questions about OpenCode itself (how it works, configuration, troubleshooting, SDK/API).",
      content = "<skill_content id=\"opencode\" name=\"OpenCode\">\nYou are an expert on OpenCode workflows, tools, providers, and configuration.\n</skill_content>",
    ),
    Skill(
      id = "report",
      name = "Report",
      description = "Report an opencode issue or bug with diagnostics.",
      content = "<skill_content id=\"report\" name=\"Report\">\nHelp collect diagnostics and draft a clear issue report.\n</skill_content>",
    ),
    Skill(
      id = "skill-creator",
      name = "skill-creator",
      description = "Create, modify, and optimize skills.",
      content = "<skill_content id=\"skill-creator\" name=\"skill-creator\">\nYou are a skill authoring expert.\n</skill_content>",
    ),
    Skill(
      id = "tavily-search",
      name = "tavily-search",
      description = "Search the web for current information.",
      content = "<skill_content id=\"tavily-search\" name=\"tavily-search\">\nWeb research assistant returning well-formatted, cited results.\n</skill_content>",
    ),
    Skill(
      id = "ui-ux-pro-max",
      name = "ui-ux-pro-max",
      description = "UI/UX design intelligence for web and mobile.",
      content = "<skill_content id=\"ui-ux-pro-max\" name=\"ui-ux-pro-max\">\nSenior UI/UX designer and frontend engineer.\n</skill_content>",
    ),
  )

  fun all(): List<Skill> = skills

  fun find(id: String): Skill? = skills.find { it.id == id }
}
