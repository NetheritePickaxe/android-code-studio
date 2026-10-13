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

import android.content.Context
import android.util.Log
import com.pickaxe.jcode.atc.project.manager.ProjectManager
import com.pickaxe.jcode.templates.AtcInterface
import com.pickaxe.jcode.templates.android.Template
import com.pickaxe.jcode.templates.android.TemplateOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates a minimal, runnable Kotlin application project.
 *
 * Like [JavaApplication] this produces a plain Gradle project launched via the `application`
 * plugin (`gradle run`), but uses the `org.jetbrains.kotlin.jvm` plugin and places sources under
 * `src/main/kotlin`. The main class points at the generated top-level `main` function.
 */
class KotlinApplication : Template {
  override val displayName = "Kotlin Application"
  override val templateType = Template.TemplateType.OTHER

  override fun configureOptions() {
    // Keep the user's Gradle DSL preference (the KTS switch). No Android options apply here.
  }

  override suspend fun create(
      context: Context,
      listener: AtcInterface.TemplateCreationListener?,
      options: TemplateOptions,
  ) =
      withContext(Dispatchers.IO) {
        try {
          val projectRoot = File(options.saveLocation, options.projectName)
          if (!projectRoot.mkdirs() && !projectRoot.exists()) {
            withContext(Dispatchers.Main) {
              listener?.onTemplateCreated(false, "Unable to create project directory.")
            }
            return@withContext
          }

          writeFile(
              File(projectRoot, "settings.gradle${if (options.useKts) "kts" else ""}"),
              settingsContent(options.projectName, options.useKts),
          )
          writeFile(
              File(projectRoot, "build.gradle${if (options.useKts) "kts" else ""}"),
              buildContent(options.packageId, options.useKts),
          )
          writeFile(File(projectRoot, "gradle.properties"), gradlePropertiesContent())

          val srcDir = File(projectRoot, "src/main/kotlin/${options.packageId.replace('.', '/')}")
          srcDir.mkdirs()
          writeFile(File(srcDir, "Main.kt"), mainContent(options.packageId, options.projectName))

          copyWrapperFiles(context, projectRoot)

          val projectManager = ProjectManager.getInstance(context)
          val projectInfo =
              projectManager.createTemplateProjectInfo(
                  projectName = options.projectName,
                  projectDir = projectRoot.absolutePath,
                  projectType = "Kotlin Application",
              )
          projectManager.addProjectBlocking(projectInfo)

          Log.d("KotlinApplication", "Project created at ${projectRoot.absolutePath}")
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                true,
                "Kotlin application project created at ${projectRoot.absolutePath}",
            )
            listener?.onTemplateCreated(true, "", projectRoot)
          }
        } catch (e: Exception) {
          Log.e("KotlinApplication", "Error creating project", e)
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(false, "Error creating project: ${e.message}")
          }
        }
      }

  private fun settingsContent(projectName: String, useKts: Boolean): String =
      if (useKts) {
        "rootProject.name = \"$projectName\"\n"
      } else {
        "rootProject.name = '$projectName'\n"
      }

  private fun buildContent(packageId: String, useKts: Boolean): String {
    // A top-level `main` in src/main/kotlin/<pkg>/Main.kt is compiled to <pkg>.MainKt.
    val mainClass = "$packageId.MainKt"
    return if (useKts) {
      """
      plugins {
          kotlin("jvm") version "2.1.0"
          application
      }

      application {
          mainClass.set("$mainClass")
      }
      """.trimIndent() + "\n"
    } else {
      """
      plugins {
          id 'org.jetbrains.kotlin.jvm' version '2.1.0'
          id 'application'
      }

      application {
          mainClass = '$mainClass'
      }
      """.trimIndent() + "\n"
    }
  }

  private fun gradlePropertiesContent(): String =
      "# Set the JVM heap size used by Gradle for this project.\n" +
          "org.gradle.jvmargs=-Xmx256m -Dfile.encoding=UTF-8\n"

  private fun mainContent(packageId: String, projectName: String): String =
      """
      package $packageId

      fun main() {
        println("Hello, world from $projectName!")
      }
      """.trimIndent() + "\n"

  /** Copy the Gradle wrapper (gradlew, gradle/wrapper) from assets into the project. */
  private fun copyWrapperFiles(context: Context, projectRoot: File) {
    try {
      copyAssetFile(context, "gradle/gradlew", File(projectRoot, "gradlew"))
      File(projectRoot, "gradlew").setExecutable(true, false)
      copyAssetFile(context, "gradle/gradlew.bat", File(projectRoot, "gradlew.bat"))
      copyAssetFolder(context, "gradle/wrapper", File(projectRoot, "gradle/wrapper"))
    } catch (e: Exception) {
      // Wrapper files are optional: the IDE can generate the wrapper on demand.
      Log.w("KotlinApplication", "Failed to copy Gradle wrapper files", e)
    }
  }

  private fun writeFile(file: File, content: String) {
    file.parentFile?.mkdirs()
    file.writeText(content)
  }

  private fun copyAssetFile(context: Context, assetPath: String, dest: File) {
    dest.parentFile?.mkdirs()
    context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
  }

  private fun copyAssetFolder(context: Context, assetPath: String, destDir: File) {
    val entries = context.assets.list(assetPath) ?: return
    destDir.mkdirs()
    for (entry in entries) {
      val subPath = "$assetPath/$entry"
      if (context.assets.list(subPath)?.isNotEmpty() == true) {
        copyAssetFolder(context, subPath, File(destDir, entry))
      } else {
        copyAssetFile(context, subPath, File(destDir, entry))
      }
    }
  }
}