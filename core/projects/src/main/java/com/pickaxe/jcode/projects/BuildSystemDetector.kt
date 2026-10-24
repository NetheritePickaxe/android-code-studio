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
package com.pickaxe.jcode.projects

import java.io.File

/**
 * Detects the build system of a project directory.
 *
 * The IDE supports three project types:
 *  - [BuildSystem.GRADLE] — a Gradle project that must be built with the Gradle Tooling API
 *    (Android projects, or Gradle projects without Java sources).
 *  - [BuildSystem.JAVA_JAR] — a plain Java project that is compiled directly with `javac`, packaged
 *    into a JAR and run. This also covers Gradle projects that only contain plain Java sources (for
 *    example the projects created by the "Java Application" template): they do not need the Gradle
 *    Tooling API at all.
 *  - [BuildSystem.JAVA_SINGLE] — a single `.java` file at the root of the project that is compiled
 *    and run directly.
 *
 * Detection is purely file-based and does not require Gradle to be present.
 */
object BuildSystemDetector {

    /** Directory names that never contain project sources. */
    private val IGNORED_DIRS =
        setOf("build", "out", "bin", ".gradle", ".jcode", ".git", ".idea", "gradle")

    /** How deep the source scan goes, to keep detection fast on large projects. */
    private const val MAX_SOURCE_DEPTH = 12

    /** How deep we look for an `AndroidManifest.xml`. */
    private const val MAX_MANIFEST_DEPTH = 6

    /**
     * Detects the build system of the given project directory.
     *
     * @param projectDir The root directory of the project.
     * @return The detected [BuildSystem], or `null` if the directory is not a usable project.
     */
    fun detect(projectDir: File): BuildSystem? {
        if (!projectDir.exists() || !projectDir.isDirectory) return null

        return when {
            // Android projects must go through the Gradle Tooling API.
            isAndroidGradleProject(projectDir) -> BuildSystem.GRADLE
            // Plain Java sources (with or without a Gradle wrapper) are built directly.
            hasJavaSources(projectDir) ->
                if (isSingleJavaFile(projectDir)) BuildSystem.JAVA_SINGLE else BuildSystem.JAVA_JAR
            // Plain Kotlin sources (with or without a Gradle wrapper) are built directly with kotlinc.
            hasKotlinSources(projectDir) ->
                if (isSingleKotlinFile(projectDir)) BuildSystem.KOTLIN_SINGLE else BuildSystem.KOTLIN_JAR
            // Gradle projects without Java/Kotlin sources keep using Gradle.
            isGradleProject(projectDir) -> BuildSystem.GRADLE
            else -> null
        }
    }

    /**
     * Returns `true` if [projectDir] is an Android project that is built with Gradle. Such projects
     * are detected by an `AndroidManifest.xml` or by the Android Gradle plugin being applied in one
     * of the build scripts.
     */
    fun isAndroidGradleProject(projectDir: File): Boolean {
        if (findAndroidManifest(projectDir) != null) return true
        return collectBuildScripts(projectDir).any { containsAndroidPlugin(it) }
    }

    /**
     * Returns `true` if [projectDir] looks like a Gradle project: it has a `gradlew` script, a
     * `gradle/wrapper/gradle-wrapper.jar`, a `gradle-wrapper.properties` and a
     * `settings.gradle[.kts]` or `build.gradle[.kts]`.
     */
    fun isGradleProject(projectDir: File): Boolean {
        val gradlew = File(projectDir, "gradlew")
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        val wrapperProps = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        return gradlew.exists() && wrapperJar.exists() && wrapperProps.exists() &&
            hasBuildScript(projectDir)
    }

    /** Returns `true` if [projectDir] has a Gradle `settings` or `build` script. */
    fun hasBuildScript(projectDir: File): Boolean =
        File(projectDir, "settings.gradle").exists() ||
            File(projectDir, "settings.gradle.kts").exists() ||
            File(projectDir, "build.gradle").exists() ||
            File(projectDir, "build.gradle.kts").exists()

    /**
     * Returns `true` if [projectDir] is a plain Java project: it contains at least one `.java`
     * source file but is not an Android project.
     */
    fun isPlainJavaProject(projectDir: File): Boolean {
        if (isAndroidGradleProject(projectDir)) return false
        return hasJavaSources(projectDir) && !isSingleJavaFile(projectDir)
    }

    /**
     * Returns `true` if [projectDir] contains exactly one `.java` file at the root level and no
     * other source file. This is the "single file test project" case.
     */
    fun isSingleJavaFile(projectDir: File): Boolean {
        val sources = collectSourceFiles(projectDir, "java")
        return sources.size == 1 && sources[0].parentFile == projectDir
    }

    /**
     * Returns `true` if [projectDir] contains exactly one `.kt` file at the root level and no
     * other source file. This is the "single file test project" case.
     */
    fun isSingleKotlinFile(projectDir: File): Boolean {
        val sources = collectSourceFiles(projectDir, "kt")
        return sources.size == 1 && sources[0].parentFile == projectDir
    }

    /** Returns `true` if [projectDir] contains at least one `.java` source file. */
    fun hasJavaSources(projectDir: File): Boolean =
        collectSourceFiles(projectDir, "java").isNotEmpty()

    /** Returns `true` if [projectDir] contains at least one `.kt` source file. */
    fun hasKotlinSources(projectDir: File): Boolean =
        collectSourceFiles(projectDir, "kt").isNotEmpty()

    /** Returns `true` if [projectDir] contains at least one `.java` or `.kt` source file. */
    fun hasSources(projectDir: File): Boolean =
        hasJavaSources(projectDir) || hasKotlinSources(projectDir)

    // ─── Internal helpers ──────────────────────────────────────────────────

    /** Collects all source files with the given [extension], skipping generated directories. */
    private fun collectSourceFiles(projectDir: File, extension: String): List<File> {
        val result = mutableListOf<File>()
        visit(projectDir, 0, MAX_SOURCE_DEPTH) { file ->
            if (file.isFile && file.extension == extension) result.add(file)
        }
        return result
    }

    /** Returns the Gradle build scripts of the root project and of its direct modules. */
    private fun collectBuildScripts(projectDir: File): List<String> {
        val scripts = mutableListOf<File>()
        val names = setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
        projectDir.listFiles()?.forEach { child ->
            if (child.isFile && child.name in names) {
                scripts.add(child)
            } else if (child.isDirectory && !child.name.startsWith(".") && child.name !in IGNORED_DIRS) {
                child.listFiles()?.forEach { nested ->
                    if (nested.isFile && nested.name in names) scripts.add(nested)
                }
            }
        }
        return scripts.mapNotNull { file ->
            try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun containsAndroidPlugin(text: String): Boolean =
        text.contains("com.android.") ||
            text.contains("alias(libs.plugins.android") ||
            text.contains("alias(libs.plugins.com.android") ||
            text.contains("android {")

    /** Looks for an `AndroidManifest.xml`, skipping generated directories. */
    private fun findAndroidManifest(projectDir: File): File? {
        var found: File? = null
        visit(projectDir, 0, MAX_MANIFEST_DEPTH) { file ->
            if (found == null && file.isFile && file.name == "AndroidManifest.xml") {
                found = file
            }
        }
        return found
    }

    /** Walks [dir] up to [maxDepth], skipping [IGNORED_DIRS] and hidden directories. */
    private fun visit(dir: File, depth: Int, maxDepth: Int, onFile: (File) -> Unit) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name.startsWith(".") || child.name in IGNORED_DIRS) continue
                visit(child, depth + 1, maxDepth, onFile)
            } else {
                onFile(child)
            }
        }
    }
}

/**
 * The build system of a project.
 */
enum class BuildSystem {
    /**
     * A Gradle project that is built through the Gradle Tooling API. This is used for Android
     * projects, and for Gradle projects that do not contain plain Java sources.
     */
    GRADLE,

    /** A plain Java project that should be compiled to a JAR and run. */
    JAVA_JAR,

    /** A single `.java` file that should be compiled and run directly. */
    JAVA_SINGLE,

    /** A plain Kotlin project that should be compiled with kotlinc to a JAR and run. */
    KOTLIN_JAR,

    /** A single `.kt` file that should be compiled and run directly with kotlinc. */
    KOTLIN_SINGLE
}
