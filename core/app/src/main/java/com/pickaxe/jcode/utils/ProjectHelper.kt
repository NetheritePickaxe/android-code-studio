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

package com.pickaxe.jcode.utils

import com.pickaxe.jcode.projects.ModuleProject
import com.pickaxe.jcode.projects.internal.ProjectManagerImpl
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Helper class for AI Agent to interact with project structure and files.
 *
 * @author Tom
 */
object ProjectHelper {

  private val log = LoggerFactory.getLogger(ProjectHelper::class.java)

  /**
   * Write a file to the correct location in the Android project structure This method handles ANY
   * Android project file type dynamically
   */
  fun writeFileToProject(
      relativePath: String,
      content: String,
      modulePath: String? = null,
  ): Boolean {
    return try {
      val targetFile = resolveProjectFilePath(relativePath, modulePath)
      if (targetFile == null) {
        log.error("Could not resolve file path: $relativePath")
        return false
      }

      // Ensure parent directories exist
      targetFile.parentFile?.mkdirs()

      // Write the content
      targetFile.writeText(content)

      log.info("Successfully wrote file: ${targetFile.absolutePath}")
      true
    } catch (e: Exception) {
      log.error("Failed to write file: $relativePath", e)
      false
    }
  }

  /**
   * Resolve a relative Android project path to an absolute File This method dynamically handles any
   * Android project structure
   */
  fun resolveProjectFilePath(relativePath: String, modulePath: String? = null): File? {
    val targetModule = getTargetModule(modulePath)
    if (targetModule == null) {
      log.error("Could not find target module: $modulePath")
      return null
    }

    // Normalize the path - remove leading "app/" if present since we'll use the actual module
    // structure
    val normalizedPath = relativePath.removePrefix("app/")

    return when {
      // Handle any res/ files (drawable, layout, values, menu, xml, anim, etc.)
      normalizedPath.startsWith("src/main/res/") -> {
        val resDir = getResourceDirectory(targetModule)
        if (resDir != null) {
          val resourcePath = normalizedPath.removePrefix("src/main/res/")
          File(resDir, resourcePath)
        } else {
          log.error("Could not find resource directory for module")
          null
        }
      }

      // Handle any Java/Kotlin source files
      normalizedPath.startsWith("src/main/java/") -> {
        val sourceDir = getSourceDirectory(targetModule)
        if (sourceDir != null) {
          val sourcePath = normalizedPath.removePrefix("src/main/java/")
          File(sourceDir, sourcePath)
        } else {
          log.error("Could not find source directory for module")
          null
        }
      }

      // Handle AndroidManifest.xml
      normalizedPath == "src/main/AndroidManifest.xml" -> {
        File(targetModule.projectDir, "src/main/AndroidManifest.xml")
      }

      // Handle assets files
      normalizedPath.startsWith("src/main/assets/") -> {
        val assetsDir = getAssetsDirectory(targetModule)
        if (assetsDir != null) {
          val assetPath = normalizedPath.removePrefix("src/main/assets/")
          File(assetsDir, assetPath)
        } else {
          // Create assets directory if it doesn't exist
          val moduleRoot = targetModule.projectDir
          val assetsDir = File(moduleRoot, "src/main/assets")
          val assetPath = normalizedPath.removePrefix("src/main/assets/")
          File(assetsDir, assetPath)
        }
      }

      // Handle any other src/main/ files (like jni, aidl, etc.)
      normalizedPath.startsWith("src/main/") -> {
        val moduleRoot = targetModule.projectDir
        File(moduleRoot, normalizedPath)
      }

      // Handle build.gradle files and other root-level files
      normalizedPath == "build.gradle" || normalizedPath == "build.gradle.kts" -> {
        File(targetModule.projectDir, normalizedPath)
      }

      // Handle proguard files and other config files
      normalizedPath.endsWith(".pro") || normalizedPath.endsWith(".cfg") -> {
        File(targetModule.projectDir, normalizedPath)
      }

      // Generic fallback: relative to module root
      else -> {
        log.info("Using generic path resolution for: $relativePath")
        File(targetModule.projectDir, normalizedPath)
      }
    }
  }

  /** Get the main resource directory for a module */
  private fun getResourceDirectory(module: ModuleProject): File? {
    return File(module.projectDir, "src/main/res")
  }

  /** Get the main source directory for a module */
  private fun getSourceDirectory(module: ModuleProject): File? {
    return File(module.projectDir, "src/main/java")
  }

  /** Get the assets directory for a module */
  private fun getAssetsDirectory(module: ModuleProject): File? {
    return File(module.projectDir, "src/main/assets")
  }

  /** Check if a file path represents a valid Android project file */
  fun isValidAndroidProjectPath(relativePath: String): Boolean {
    val normalizedPath = relativePath.removePrefix("app/")

    return when {
      normalizedPath.startsWith("src/main/res/") -> true
      normalizedPath.startsWith("src/main/java/") -> true
      normalizedPath.startsWith("src/main/assets/") -> true
      normalizedPath == "src/main/AndroidManifest.xml" -> true
      normalizedPath == "build.gradle" -> true
      normalizedPath == "build.gradle.kts" -> true
      normalizedPath.startsWith("src/main/") -> true // Any other src/main files
      else -> false
    }
  }

  /** Get the expected file type based on the path */
  fun getFileType(relativePath: String): String {
    val normalizedPath = relativePath.removePrefix("app/")

    return when {
      normalizedPath.contains("/res/drawable/") -> "Drawable Resource"
      normalizedPath.contains("/res/layout/") -> "Layout Resource"
      normalizedPath.contains("/res/values/") -> "Values Resource"
      normalizedPath.contains("/res/menu/") -> "Menu Resource"
      normalizedPath.contains("/res/xml/") -> "XML Resource"
      normalizedPath.contains("/res/anim/") -> "Animation Resource"
      normalizedPath.contains("/res/color/") -> "Color Resource"
      normalizedPath.contains("/res/mipmap/") -> "Mipmap Resource"
      normalizedPath.contains("/java/") && normalizedPath.endsWith(".kt") -> "Kotlin Source"
      normalizedPath.contains("/java/") && normalizedPath.endsWith(".java") -> "Java Source"
      normalizedPath.contains("/assets/") -> "Asset File"
      normalizedPath == "src/main/AndroidManifest.xml" -> "Android Manifest"
      normalizedPath.startsWith("build.gradle") -> "Gradle Build File"
      else -> "Project File"
    }
  }

  /** Find strings.xml file in the project */
  fun findStringsXml(modulePath: String? = null): File? = null

  /** Find colors.xml file in the project */
  fun findColorsXml(modulePath: String? = null): File? = null

  /** Find any resource file by name */
  fun findResourceFile(fileName: String, modulePath: String? = null): File? = null

  /** Find layout file by name */
  fun findLayoutFile(layoutName: String, modulePath: String? = null): File? = null

  /** Get all source directories for a module */
  fun getSourceDirectories(modulePath: String? = null): List<File> = emptyList()

  /** Get package name for a module */
  fun getPackageName(modulePath: String? = null): String? = null

  /** Create or update strings.xml with new string resource */
  fun addStringResource(name: String, value: String, modulePath: String? = null): Boolean = false

  /** Create or update colors.xml with new color resource */
  fun addColorResource(name: String, value: String, modulePath: String? = null): Boolean = false

  /** Get the AndroidManifest.xml file for a module */
  fun getManifestFile(modulePath: String? = null): File? = null

  /** Add permission to AndroidManifest.xml */
  fun addPermission(permission: String, modulePath: String? = null): Boolean = false

  /** Get all modules in the project */
  fun getAndroidModules(): List<com.pickaxe.jcode.projects.ModuleProject> {
    val projectManager = ProjectManagerImpl.getInstance()
    val workspace = projectManager.getWorkspace() ?: return emptyList()
    return workspace.getSubProjects().filterIsInstance<com.pickaxe.jcode.projects.ModuleProject>()
  }

  /** Find the module that contains a specific file */
  fun findModuleForFile(file: File): com.pickaxe.jcode.projects.ModuleProject? {
    val projectManager = ProjectManagerImpl.getInstance()
    val workspace = projectManager.getWorkspace() ?: return null
    val foundModule = workspace.findModuleForFile(file, false)
    return if (foundModule is com.pickaxe.jcode.projects.ModuleProject) foundModule else null
  }

  /** Get project root directory */
  fun getProjectRoot(): File {
    return ProjectManagerImpl.getInstance().projectDir
  }

  private fun getTargetModule(modulePath: String?): ModuleProject? {
    val projectManager = ProjectManagerImpl.getInstance()
    val workspace = projectManager.getWorkspace() ?: return null

    val modules = workspace.getSubProjects().filterIsInstance<ModuleProject>()

    return if (modulePath != null) {
      modules.find { it.path == modulePath }
    } else {
      modules.find { it.path == ":app" } ?: modules.firstOrNull()
    }
  }
}
