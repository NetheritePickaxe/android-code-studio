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

package com.pickaxe.jcode.projects.internal

import com.pickaxe.jcode.projects.GradleProject
import com.pickaxe.jcode.projects.java.JavaModule
import com.pickaxe.jcode.tooling.api.IAndroidProject
import com.pickaxe.jcode.tooling.api.IGradleProject
import com.pickaxe.jcode.tooling.api.IJavaProject
import com.pickaxe.jcode.tooling.api.IProject
import com.pickaxe.jcode.tooling.api.ProjectType
import com.pickaxe.jcode.tooling.api.models.AndroidProjectMetadata
import com.pickaxe.jcode.tooling.api.models.BasicProjectMetadata
import com.pickaxe.jcode.tooling.api.models.JavaProjectMetadata
import com.pickaxe.jcode.tooling.api.models.params.StringParameter
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transforms project models from tooling API to the projects API.
 *
 * @author Akash Yadav
 */
internal object WorkspaceModelBuilder {

  private val log = LoggerFactory.getLogger(WorkspaceModelBuilder::class.java)

  fun build(
    projectDir: File,
    project: IProject
  ): WorkspaceImpl? {
    try {
      val allProjects = project.getProjects().get()
      val selectionResult = project.selectProject(StringParameter("")).get()
      check(selectionResult.isSuccessful) {
        "Cannot find root project"
      }

      val rootProject = when (project.getType().get()) {
        ProjectType.Gradle -> transform(com.pickaxe.jcode.tooling.api.IGradleProject::class.java.cast(project))
        ProjectType.Android -> transform(com.pickaxe.jcode.tooling.api.IGradleProject::class.java.cast(project))
        else -> throw IllegalStateException(
          "Root project must be either an Android project or a Gradle project"
        )
      }

      return WorkspaceImpl(
        projectDir,
        rootProject,
        CopyOnWriteArrayList(transform(allProjects, project)),
        project.getProjectSyncIssues().get()
      )
    } catch (error: Throwable) {
      log.error("Unable to transform project", error)
      return null
    }
  }

  private fun transform(rootProject: IGradleProject): GradleProject {
    val metadata = rootProject.getMetadata().get()
    return GradleProject(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,

      // The list will never change, we could make these thread-safe with
      // CopyOnWriteArrayList
      tasks = CopyOnWriteArrayList(rootProject.getTasks().get() ?: listOf()),
    )
  }

    private fun transform(project: com.pickaxe.jcode.tooling.api.IAndroidProject): com.pickaxe.jcode.projects.GradleProject {
    return transform(com.pickaxe.jcode.tooling.api.IGradleProject::class.java.cast(project) as com.pickaxe.jcode.tooling.api.IGradleProject)
  }



  private fun transform(project: IJavaProject): JavaModule {
    val metadata = project.getMetadata().get() as JavaProjectMetadata
    return JavaModule(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,
      tasks = project.getTasks().get(),
      contentRoots = project.getContentRoots().get(),
      dependencies = project.getDependencies().get(),
      compilerSettings = metadata.compilerSettings,
      classesJar = metadata.classesJar
    )
  }

  private fun transform(modules: List<BasicProjectMetadata>, root: IProject): List<GradleProject> {
    return mutableListOf<GradleProject>().apply {
      for (module in modules) {
        add(createProject(module, root))
      }
    }
  }

  private fun createProject(moduleMetadata: BasicProjectMetadata, root: IProject): GradleProject {
    val selectionResult = root.selectProject(StringParameter(moduleMetadata.projectPath)).get()
    check(selectionResult.isSuccessful) {
      "Selection failed for project '${moduleMetadata.projectPath}' but it is included in all projects."
    }

    val type = root.getType().get() ?: throw java.lang.IllegalStateException("Invalid module data")

    return when (type) {
      ProjectType.Gradle,
      ProjectType.Unknown -> transform(root.asGradleProject())

      ProjectType.Android -> transform(root.asAndroidProject())
      ProjectType.Java -> transform(root.asJavaProject())
    }
  }
}
