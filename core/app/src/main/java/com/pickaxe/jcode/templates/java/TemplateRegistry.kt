/*
 *  This file is part of jcode.
 *
 *  jcode is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  jcode is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with jcode.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.templates.java

import com.pickaxe.jcode.templates.android.Template

/**
 * Registry of project templates offered by the "New Project" wizard.
 *
 * jcode creates plain Java/Kotlin Gradle projects that run with the `application` plugin
 * (`gradle run`); Android templates are intentionally not registerable here.
 */
object TemplateRegistry {
  private val templates = mutableListOf<Template>()

  init {
    register(JavaApplication())
    register(KotlinApplication())
  }

  fun register(template: Template) {
    templates.add(template)
  }

  fun getAllTemplates(): List<Template> = templates.toList()
}