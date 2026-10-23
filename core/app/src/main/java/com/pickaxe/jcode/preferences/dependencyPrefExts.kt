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
package com.pickaxe.jcode.preferences

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pickaxe.jcode.dependencies.DependencyScope
import com.pickaxe.jcode.dependencies.DependencyStore
import com.pickaxe.jcode.dependencies.GradleDependencyApplier
import com.pickaxe.jcode.dependencies.JdkSetupLauncher
import com.pickaxe.jcode.projects.internal.ProjectManagerImpl
import com.pickaxe.jcode.resources.R.string
import com.pickaxe.jcode.utils.Environment
import com.pickaxe.jcode.utils.flashError
import com.pickaxe.jcode.utils.flashSuccess
import java.io.File
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * Settings screen for managing the dependencies of the three project types supported by the IDE.
 */
@Parcelize
class DependencyManagementPreferences(
    override val key: String = "idepref_dependency_management",
    override val title: Int = string.dependency_management_title,
    override val summary: Int? = string.dependency_management_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(JavaDependencyScreen())
    addPreference(KotlinDependencyScreen())
    addPreference(GradleDependencyScreen())
  }
}

@Parcelize
private class JavaDependencyScreen(
    override val key: String = "idepref_deps_java",
    override val title: Int = string.dependency_management_java,
    override val summary: Int? = string.dependency_management_java_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(
        DependencyListPreference(
            key = "idepref_deps_java_repositories",
            title = string.dependency_repositories_title,
            scopeName = DependencyScope.JAVA.name,
            repositories = true,
        )
    )
    addPreference(
        DependencyListPreference(
            key = "idepref_deps_java_dependencies",
            title = string.dependency_list_title,
            scopeName = DependencyScope.JAVA.name,
            repositories = false,
        )
    )
    addPreference(JdkStatusPreference())
    addPreference(InstallJdkPreference())
  }
}

@Parcelize
private class KotlinDependencyScreen(
    override val key: String = "idepref_deps_kotlin",
    override val title: Int = string.dependency_management_kotlin,
    override val summary: Int? = string.dependency_management_kotlin_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(
        DependencyListPreference(
            key = "idepref_deps_kotlin_repositories",
            title = string.dependency_repositories_title,
            scopeName = DependencyScope.KOTLIN.name,
            repositories = true,
        )
    )
    addPreference(
        DependencyListPreference(
            key = "idepref_deps_kotlin_dependencies",
            title = string.dependency_list_title,
            scopeName = DependencyScope.KOTLIN.name,
            repositories = false,
        )
    )
  }
}

@Parcelize
private class GradleDependencyScreen(
    override val key: String = "idepref_deps_gradle",
    override val title: Int = string.dependency_management_gradle,
    override val summary: Int? = string.dependency_management_gradle_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(
        DependencyListPreference(
            key = "idepref_deps_gradle_repositories",
            title = string.dependency_repositories_title,
            scopeName = DependencyScope.GRADLE.name,
            repositories = true,
        )
    )
    addPreference(
        DependencyListPreference(
            key = "idepref_deps_gradle_dependencies",
            title = string.dependency_list_title,
            scopeName = DependencyScope.GRADLE.name,
            repositories = false,
            gradle = true,
        )
    )
    addPreference(ApplyGradleDependenciesPreference())
    addPreference(ProjectDependenciesFilePreference())
  }
}

/**
 * A preference that edits a list of entries (repositories or dependencies) in a multi-line dialog.
 *
 * @param scopeName The [DependencyScope] this list belongs to.
 * @param repositories Whether this list contains repositories instead of dependencies.
 * @param gradle Whether the entries are Gradle dependency declarations.
 */
@Parcelize
private class DependencyListPreference(
    override val key: String,
    override val title: Int,
    private val scopeName: String,
    private val repositories: Boolean,
    private val gradle: Boolean = false,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  private val scope: DependencyScope
    get() = DependencyScope.valueOf(scopeName)

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context).apply {
      summary = buildSummary(context)
    }.also { preference = it }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val entries =
        if (repositories) {
          DependencyStore.getRepositories(scope)
        } else {
          DependencyStore.getDependencies(scope)
        }

    val input =
        EditText(context).apply {
          setText(entries.joinToString("\n"))
          hint =
              context.getString(
                  if (repositories) string.dependency_dialog_hint_repository
                  else string.dependency_dialog_hint_coordinates
              )
          minLines = 6
          maxLines = 12
          gravity = Gravity.TOP or Gravity.START
          setHorizontallyScrolling(false)
          inputType =
              InputType.TYPE_CLASS_TEXT or
                  InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                  InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

    val container = FrameLayout(context).apply {
      val padding = (16 * context.resources.displayMetrics.density).toInt()
      setPadding(padding, padding / 2, padding, 0)
      addView(input)
    }

    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(
            if (gradle) string.dependency_list_summary_gradle
            else if (repositories) string.dependency_repositories_summary
            else string.dependency_list_summary
        )
        .setView(container)
        .setPositiveButton(string.save) { _, _ ->
          val lines = DependencyStore.splitLines(input.text.toString())
          if (repositories) {
            DependencyStore.setRepositories(scope, lines)
          } else {
            DependencyStore.setDependencies(scope, lines)
          }
          preference.summary = buildSummary(context)
        }
        .setNegativeButton(string.cancel, null)
        .show()
    return true
  }

  private fun buildSummary(context: Context): String {
    val count =
        if (repositories) DependencyStore.getRepositories(scope).size
        else DependencyStore.getDependencies(scope).size
    return if (count == 0) context.getString(string.dependency_summary_empty)
    else context.getString(string.dependency_summary_count, count)
  }
}

/** Shows whether a JDK is available for compiling and running Java projects. */
@Parcelize
private class JdkStatusPreference(
    override val key: String = "idepref_deps_java_jdk",
    override val title: Int = string.dependency_jdk_title,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context).apply { summary = buildSummary(context) }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val home = Environment.JAVA_HOME
    val javac = home?.let { File(it, "bin/javac") }
    val java = Environment.JAVA

    val message =
        if (javac?.exists() == true && java != null && java.exists()) {
          context.getString(
              string.dependency_jdk_dialog_ok,
              home!!.absolutePath,
              javac.absolutePath,
              java.absolutePath,
          )
        } else {
          context.getString(string.dependency_jdk_dialog_missing, home?.absolutePath ?: "-")
        }

    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(string.save, null)
        .show()
    return true
  }

  private fun buildSummary(context: Context): String {
    val home = Environment.JAVA_HOME
    val javac = home?.let { File(it, "bin/javac") }
    val java = Environment.JAVA
    val available = javac?.exists() == true && java != null && java.exists()
    return if (available) context.getString(string.dependency_jdk_found)
    else context.getString(string.dependency_jdk_missing)
  }
}

/** Writes the configured Gradle repositories and dependencies into the opened project. */
@Parcelize
private class ApplyGradleDependenciesPreference(
    override val key: String = "idepref_deps_gradle_apply",
    override val title: Int = string.dependency_apply_gradle_title,
    override val summary: Int? = string.dependency_apply_gradle_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context)
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val projectDir = currentProjectDir()
    if (projectDir == null) {
      flashError(context.getString(string.dependency_apply_no_project))
      return true
    }

    val repositories = DependencyStore.getRepositories(DependencyScope.GRADLE)
    val dependencies = DependencyStore.getDependencies(DependencyScope.GRADLE)
    val result = GradleDependencyApplier.apply(projectDir, repositories, dependencies)

    if (result.success) {
      flashSuccess(context.getString(string.dependency_apply_success, result.script?.name ?: ""))
    } else if (result.error == "no_build_script") {
      flashError(context.getString(string.dependency_apply_no_build_script))
    } else {
      flashError(context.getString(string.dependency_apply_failed, result.error ?: ""))
    }
    return true
  }

  private fun currentProjectDir(): File? {
    return try {
      ProjectManagerImpl.getInstance().projectDir
    } catch (e: Exception) {
      null
    }
  }
}

/** Explains where project specific dependencies can be declared. */
@Parcelize
private class ProjectDependenciesFilePreference(
    override val key: String = "idepref_deps_project_file",
    override val title: Int = string.dependency_project_file_title,
    override val summary: Int? = string.dependency_project_file_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context)
  }
}

/** Opens the terminal and runs the `idesetup` script to install the JDK and Gradle. */
@Parcelize
private class InstallJdkPreference(
    override val key: String = "idepref_deps_java_install_jdk",
    override val title: Int = string.dependency_install_jdk_title,
    override val summary: Int? = string.dependency_install_jdk_summary,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context)
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val activity = preference.context as? android.app.Activity
    if (activity != null) {
      JdkSetupLauncher.launch(activity)
    } else {
      flashError("Cannot start the terminal from here.")
    }
    return true
  }
}
