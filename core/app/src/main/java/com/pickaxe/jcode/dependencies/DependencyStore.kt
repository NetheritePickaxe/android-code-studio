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
package com.pickaxe.jcode.dependencies

import com.pickaxe.jcode.preferences.internal.prefManager
import com.pickaxe.jcode.utils.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.slf4j.LoggerFactory

/**
 * The kind of project a dependency list belongs to.
 *
 * Java and Kotlin dependencies are used as a compile/run classpath by the direct build backend.
 * Gradle dependencies are applied to the opened Gradle project's build script.
 */
enum class DependencyScope(val prefPrefix: String) {
  JAVA("deps_java"),
  KOTLIN("deps_kotlin"),
  GRADLE("deps_gradle");

  val repositoriesKey: String
    get() = "${prefPrefix}_repositories"

  val dependenciesKey: String
    get() = "${prefPrefix}_dependencies"
}

/**
 * Stores and resolves the dependencies configured in the settings.
 *
 * Entries are either:
 *  - Maven coordinates in the form `groupId:artifactId:version`, or
 *  - absolute paths to local `.jar` files, or
 *  - paths relative to the project directory.
 *
 * Maven artifacts are downloaded on demand into `$HOME/.jcode/deps` and cached there. Transitive
 * dependencies of the configured artifacts are **not** resolved automatically; add them explicitly
 * if you need them.
 */
object DependencyStore {

  private val log = LoggerFactory.getLogger(DependencyStore::class.java)

  /** Repositories used for Java and Kotlin dependencies. */
  val DEFAULT_REPOSITORIES = listOf("https://repo1.maven.org/maven2")

  /** Repositories used for Gradle dependencies. */
  val DEFAULT_GRADLE_REPOSITORIES =
      listOf("https://repo1.maven.org/maven2", "https://dl.google.com/dl/android/maven2")

  /** Directory (relative to the project) that can hold project specific dependencies. */
  const val PROJECT_DEPS_FILE = ".jcode/dependencies.txt"

  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 60_000

  // ─── Reading and writing ──────────────────────────────────────────────

  fun defaultRepositories(scope: DependencyScope): List<String> =
      if (scope == DependencyScope.GRADLE) DEFAULT_GRADLE_REPOSITORIES else DEFAULT_REPOSITORIES

  fun getRepositories(scope: DependencyScope): List<String> {
    val stored = prefManager.getString(scope.repositoriesKey, null)
    if (stored.isNullOrBlank()) return defaultRepositories(scope)
    return splitLines(stored)
  }

  fun setRepositories(scope: DependencyScope, repositories: List<String>) {
    prefManager.putString(scope.repositoriesKey, repositories.joinToString("\n"))
  }

  fun getDependencies(scope: DependencyScope): List<String> {
    return splitLines(prefManager.getString(scope.dependenciesKey, "") ?: "")
  }

  fun setDependencies(scope: DependencyScope, dependencies: List<String>) {
    prefManager.putString(scope.dependenciesKey, dependencies.joinToString("\n"))
  }

  /** Splits a multi-line text field into entries, dropping blank lines and comments. */
  fun splitLines(text: String): List<String> =
      text.split('\n')
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
          .distinct()

  /** Dependencies declared by the project itself in [PROJECT_DEPS_FILE]. */
  fun getProjectDependencies(projectDir: File): List<String> {
    val file = File(projectDir, PROJECT_DEPS_FILE)
    if (!file.isFile) return emptyList()
    return try {
      splitLines(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
      log.warn("Failed to read project dependencies from {}", file, e)
      emptyList()
    }
  }

  /** Global dependencies followed by the project's own dependencies. */
  fun allDependencies(scope: DependencyScope, projectDir: File?): List<String> {
    val global = getDependencies(scope)
    val local = projectDir?.let { getProjectDependencies(it) } ?: emptyList()
    return (global + local).distinct()
  }

  // ─── Resolution ───────────────────────────────────────────────────────

  /**
   * Resolves [scope]'s dependencies (global and project specific) into JAR files.
   *
   * @param projectDir The project directory, or `null` to only use global dependencies.
   * @param onMessage Receives human readable progress and error messages.
   * @return The JAR files that exist on disk after resolution.
   */
  fun resolveJars(
      scope: DependencyScope,
      projectDir: File?,
      onMessage: (String) -> Unit = {},
  ): List<File> {
    val entries = allDependencies(scope, projectDir)
    if (entries.isEmpty()) return emptyList()

    val jars = mutableListOf<File>()
    for (entry in entries) {
      val jar = resolveEntry(entry, projectDir, getRepositories(scope), onMessage)
      if (jar != null) {
        jars.add(jar)
      } else {
        onMessage("Dependency not available: $entry")
      }
    }
    return jars.distinct()
  }

  /** Resolves a single entry (coordinates or file path) into a JAR file. */
  fun resolveEntry(
      entry: String,
      projectDir: File?,
      repositories: List<String> = DEFAULT_REPOSITORIES,
      onMessage: (String) -> Unit = {},
  ): File? {
    val coordinates = parseCoordinates(entry)
    if (coordinates == null) {
      // A path to a local JAR, absolute or relative to the project.
      val file = if (File(entry).isAbsolute) File(entry) else File(projectDir ?: File("."), entry)
      return if (file.isFile) file else null
    }

    val (group, artifact, version) = coordinates
    val cached = cachedJar(group, artifact, version)
    if (cached.isFile) return cached

    for (repository in repositories) {
      val url = artifactUrl(repository, group, artifact, version)
      onMessage("Downloading $entry from $repository ...")
      try {
        download(url, cached)
        if (cached.isFile && cached.length() > 0) {
          onMessage("Downloaded $entry")
          return cached
        }
      } catch (e: Exception) {
        log.warn("Failed to download {} from {}", entry, repository, e)
        cached.delete()
      }
    }
    return null
  }

  /** Parses `group:artifact:version` into its parts, or returns `null` for anything else. */
  fun parseCoordinates(entry: String): Triple<String, String, String>? {
    val parts = entry.split(':')
    if (parts.size != 3) return null
    val group = parts[0].trim()
    val artifact = parts[1].trim()
    val version = parts[2].trim()
    if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) return null
    if (group.contains('/') || artifact.contains('/')) return null
    return Triple(group, artifact, version)
  }

  /** The cache location of a Maven artifact. */
  fun cachedJar(group: String, artifact: String, version: String): File {
    return File(
        File(Environment.HOME, ".jcode/deps"),
        "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar",
    )
  }

  private fun artifactUrl(
      repository: String,
      group: String,
      artifact: String,
      version: String,
  ): String {
    val base = repository.trim().trimEnd('/')
    return "$base/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"
  }

  private fun download(url: String, target: File) {
    target.parentFile?.mkdirs()
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    connection.requestMethod = "GET"
    try {
      val code = connection.responseCode
      if (code !in 200..299) {
        throw IllegalStateException("HTTP $code for $url")
      }
      val temp = File(target.parentFile, "${target.name}.part")
      connection.inputStream.use { input ->
        temp.outputStream().use { output -> input.copyTo(output) }
      }
      if (!temp.renameTo(target)) {
        temp.copyTo(target, overwrite = true)
        temp.delete()
      }
    } finally {
      connection.disconnect()
    }
  }
}
