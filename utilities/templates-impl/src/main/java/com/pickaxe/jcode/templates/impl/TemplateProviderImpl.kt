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

package com.pickaxe.jcode.templates.impl

import com.google.auto.service.AutoService
import com.google.common.collect.ImmutableList
import com.pickaxe.jcode.templates.ITemplateProvider
import com.pickaxe.jcode.templates.Template
import com.pickaxe.jcode.templates.impl.basicActivity.basicActivityProject
import com.pickaxe.jcode.templates.impl.bottomNavActivity.bottomNavActivityProject
import com.pickaxe.jcode.templates.impl.composeActivity.composeActivityProject
import com.pickaxe.jcode.templates.impl.emptyActivity.emptyActivityProject
import com.pickaxe.jcode.templates.impl.navDrawerActivity.navDrawerActivityProject
import com.pickaxe.jcode.templates.impl.noActivity.noActivityProjectTemplate
import com.pickaxe.jcode.templates.impl.noAndroidXActivity.noAndroidXActivityProject
import com.pickaxe.jcode.templates.impl.tabbedActivity.tabbedActivityProject
import com.pickaxe.jcode.templates.impl.withCpp.withCppProject

/**
 * Default implementation of the [ITemplateProvider].
 *
 * @author Akash Yadav
 */
@Suppress("unused")
@AutoService(ITemplateProvider::class)
class TemplateProviderImpl : ITemplateProvider {

  private val templates = mutableMapOf<String, Template<*>>()

  init {
    initializeTemplates()
  }

  private fun templates() =
      // @formatter:off
      arrayOf(
          noActivityProjectTemplate(),
          emptyActivityProject(),
          withCppProject(),
          basicActivityProject(),
          navDrawerActivityProject(),
          bottomNavActivityProject(),
          tabbedActivityProject(),
          noAndroidXActivityProject(),
          composeActivityProject(),
      )

  private fun initializeTemplates() {
    templates().forEach { template -> templates[template.templateId] = template }
  }

  // @formatter:on

  override fun getTemplates(): List<Template<*>> {
    return ImmutableList.copyOf(templates.values)
  }

  override fun getTemplate(templateId: String): Template<*>? {
    return templates[templateId]
  }

  override fun reload() {
    release()
    initializeTemplates()
  }

  override fun release() {
    templates.forEach { it.value.release() }
    templates.clear()
  }
}
