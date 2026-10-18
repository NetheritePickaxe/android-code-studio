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
 *  - [BuildSystem.GRADLE] — a standard Gradle project (with `gradlew`, `settings.gradle` and
 *    `build.gradle[.kts]`). Built through the Gradle Tooling API.
 *  - [BuildSystem.JAVA_JAR] — a plain Java project that should be compiled to a JAR and run.
 *  - [BuildSystem.JAVA_SINGLE] — a single `.java` file that should be compiled and run directly.
 *
 * Detection is purely file-based and does not require Gradle to be present.
 */
object BuildSystemDetector {

    /**
     * Detects the build system of the given project directory.
     *
     * @param projectDir The root directory of the project.
     * @return The detected [BuildSystem], or `null` if the directory is not a usable project.
     */
    fun detect(projectDir: File): BuildSystem? {
        if (!projectDir.exists() || !projectDir.isDirectory) return null

        return when {
            isGradleProject(projectDir) -> BuildSystem.GRADLE
            isPlainJavaProject(projectDir) -> BuildSystem.JAVA_JAR
            isSingleJavaFile(projectDir) -> BuildSystem.JAVA_SINGLE
            else -> null
        }
    }

    /**
     * Returns `true` if [projectDir] looks like a Gradle project: it has a `gradlew` script, a
     * `gradle/wrapper/gradle-wrapper.jar` and a `gradle-wrapper.properties`.
     */
    fun isGradleProject(projectDir: File): Boolean {
        val gradlew = File(projectDir, "gradlew")
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        val wrapperProps = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        val settingsGradle =
            File(projectDir, "settings.gradle").exists() ||
                File(projectDir, "settings.gradle.kts").exists()
        val buildGradle =
            File(projectDir, "build.gradle").exists() ||
                File(projectDir, "build.gradle.kts").exists()
        return gradlew.exists() && wrapperJar.exists() && wrapperProps.exists() &&
            (settingsGradle || buildGradle)
    }

    /**
     * Returns `true` if [projectDir] is a plain Java project: it contains at least one `.java`
     * source file but no Gradle wrapper.
     */
    fun isPlainJavaProject(projectDir: File): Boolean {
        if (isGradleProject(projectDir)) return false
        return hasJavaSources(projectDir) && !isSingleJavaFile(projectDir)
    }

    /**
     * Returns `true` if [projectDir] contains exactly one `.java` file at the root level and no
     * other source files. This is the "single file test project" case.
     */
    fun isSingleJavaFile(projectDir: File): Boolean {
        if (isGradleProject(projectDir)) return false
        val javaFiles = projectDir.listFiles { it.extension == "java" } ?: return false
        if (javaFiles.size != 1) return false
        // Make sure it's not just a stray file in a directory that also contains sources
        return !hasJavaSourcesInSubdirectories(projectDir)
    }

    private fun hasJavaSources(projectDir: File): Boolean {
        return projectDir.walkTopDown().any { it.extension == "java" }
    }

    private fun hasJavaSourcesInSubdirectories(projectDir: File): Boolean {
        return projectDir.walkTopDown().maxDepth(1).any {
            it != projectDir && it.extension == "java"
        }
    }
}

/**
 * The build system of a project.
 */
enum class BuildSystem {
    /** A standard Gradle project, built through the Gradle Tooling API. */
    GRADLE,

    /** A plain Java project that should be compiled to a JAR and run. */
    JAVA_JAR,

    /** A single `.java` file that should be compiled and run directly. */
    JAVA_SINGLE
}