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
package com.pickaxe.jcode.projects.builder.kotlin

import com.pickaxe.jcode.dependencies.DependencyScope
import com.pickaxe.jcode.dependencies.DependencyStore
import com.pickaxe.jcode.projects.BuildSystem
import com.pickaxe.jcode.projects.BuildSystemDetector
import com.pickaxe.jcode.utils.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Builds and runs Kotlin projects that do not use Gradle.
 *
 * This backend handles two project types:
 *  - [BuildSystem.KOTLIN_SINGLE] — a single `.kt` file is compiled and run directly.
 *  - [BuildSystem.KOTLIN_JAR] — all `.kt` sources are compiled, packaged into a JAR, and run.
 *
 * It uses `kotlinc` and `java` from the Termux environment. A Kotlin compiler (`kotlinc`) must be
 * installed. The KOTLIN scope dependencies configured in settings are added to the classpath.
 */
class KotlinBuildBackend(
    private val outputCallback: (String) -> Unit,
) {

    companion object {
        private val log = LoggerFactory.getLogger(KotlinBuildBackend::class.java)

        /** Matches a `package` declaration. */
        private val PACKAGE_REGEX = Regex("^\\s*package\\s+([\\w.]+)\\s*$", RegexOption.MULTILINE)

        /** Regex that finds a `fun main` declaration. */
        private val MAIN_FUNCTION_REGEX =
            Regex(
                "(?:public\\s+)?fun\\s+main\\s*\\(",
                RegexOption.DOT_MATCHES_ALL,
            )

        /** Regex that finds a class declaration followed by `main`. */
        private val MAIN_IN_CLASS_REGEX =
            Regex(
                "(?:public\\s+|internal\\s+|private\\s+)?(?:class|object)\\s+(\\w+).*?fun\\s+main\\s*\\(",
                RegexOption.DOT_MATCHES_ALL,
            )
    }

    /** Result of a build/run operation. */
    data class Result(val success: Boolean, val message: String)

    /**
     * Compiles and runs the project in [projectDir].
     */
    suspend fun buildAndRun(projectDir: File): Result =
        withContext(Dispatchers.IO) {
            val buildSystem = BuildSystemDetector.detect(projectDir)
            if (buildSystem == null) {
                return@withContext Result(
                    false,
                    "Not a recognizable project: ${projectDir.absolutePath}",
                )
            }

            when (buildSystem) {
                BuildSystem.KOTLIN_SINGLE -> runSingleFile(projectDir)
                BuildSystem.KOTLIN_JAR -> runJarProject(projectDir)
                BuildSystem.GRADLE -> {
                    outputCallback("Falling back to direct kotlinc build...")
                    if (BuildSystemDetector.isSingleKotlinFile(projectDir)) {
                        runSingleFile(projectDir)
                    } else {
                        runJarProject(projectDir)
                    }
                }
                else -> {
                    Result(
                        false,
                        "This project type is not supported by the Kotlin direct build backend.",
                    )
                }
            }
        }

    private suspend fun runSingleFile(projectDir: File): Result = withContext(Dispatchers.IO) {
        val ktFile = projectDir.listFiles { it.extension == "kt" }?.firstOrNull()
            ?: collectKotlinSources(projectDir).firstOrNull()
            ?: return@withContext Result(false, "No .kt file found in ${projectDir.name}")

        val kotlinc = kotlincBinary() ?: return@withContext Result(false, kotlincNotFoundMessage())
        val java = javaBinary() ?: return@withContext Result(false, javacNotFoundMessage())

        val dependencies = resolveDependencies(projectDir)

        val outputDir = File(projectDir, ".jcode/classes")
        outputDir.mkdirs()

        val mainClass = findMainClass(ktFile) ?: "${ktFile.nameWithoutExtension}Kt"

        outputCallback("Compiling ${ktFile.name}...")
        val compileCmd = mutableListOf(
            kotlinc.absolutePath,
            "-encoding", "UTF-8",
            "-d", outputDir.absolutePath,
        )
        if (dependencies.isNotEmpty()) {
            compileCmd.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        }
        compileCmd.add(ktFile.absolutePath)
        val compileResult = runProcess(compileCmd, projectDir)
        if (!compileResult.success) {
            outputCallback("Compilation failed.")
            return@withContext Result(false, "Compilation failed (exit code ${compileResult.exitCode})")
        }
        outputCallback("Compilation successful.")
        outputCallback("Running $mainClass...")

        val runCmd = listOf(
            java.absolutePath,
            "-Dfile.encoding=UTF-8",
            "-cp", (listOf(outputDir) + dependencies).joinToString(File.pathSeparator),
            mainClass,
        )
        val runResult = runProcess(runCmd, projectDir)
        outputCallback("Process exited with code ${runResult.exitCode}.")
        Result(runResult.success, if (runResult.success) "Run completed." else "Run failed (exit code ${runResult.exitCode}).")
    }

    private suspend fun runJarProject(projectDir: File): Result = withContext(Dispatchers.IO) {
        val kotlinc = kotlincBinary() ?: return@withContext Result(false, kotlincNotFoundMessage())
        val java = javaBinary() ?: return@withContext Result(false, javacNotFoundMessage())
        val jar = jarBinary() ?: return@withContext Result(false, javacNotFoundMessage())

        val sourceFiles = collectKotlinSources(projectDir)
        if (sourceFiles.isEmpty()) {
            return@withContext Result(false, "No .kt source files found.")
        }

        val dependencies = resolveDependencies(projectDir)

        val outputDir = File(projectDir, ".jcode/classes")
        outputDir.mkdirs()
        val buildDir = File(projectDir, ".jcode/build")
        buildDir.mkdirs()

        val mainClass = findMainClassInSources(sourceFiles) ?: run {
            outputCallback("No main class found (no 'fun main' method).")
            return@withContext Result(false, "No main class found.")
        }
        outputCallback("Detected main class: $mainClass")

        outputCallback("Compiling ${sourceFiles.size} Kotlin source file(s)...")
        val compileCmd = mutableListOf(
            kotlinc.absolutePath,
            "-encoding", "UTF-8",
            "-d", outputDir.absolutePath,
        )
        if (dependencies.isNotEmpty()) {
            compileCmd.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        }
        compileCmd.addAll(sourceFiles.map { it.absolutePath })
        val compileResult = runProcess(compileCmd, projectDir)
        if (!compileResult.success) {
            outputCallback("Compilation failed.")
            return@withContext Result(false, "Compilation failed (exit code ${compileResult.exitCode})")
        }
        outputCallback("Compilation successful.")

        val manifestFile = File(buildDir, "MANIFEST.MF")
        manifestFile.writeText("Manifest-Version: 1.0\nMain-Class: $mainClass\n\n")

        val jarName = "${projectDir.name}.jar"
        val jarFile = File(buildDir, jarName)
        outputCallback("Packaging $jarName...")
        val jarCmd = listOf(
            jar.absolutePath,
            "cfm",
            jarFile.absolutePath,
            manifestFile.absolutePath,
            "-C", outputDir.absolutePath,
            ".",
        )
        val jarResult = runProcess(jarCmd, projectDir)
        if (!jarResult.success) {
            outputCallback("JAR packaging failed.")
            return@withContext Result(false, "JAR packaging failed (exit code ${jarResult.exitCode})")
        }
        outputCallback("JAR created: ${jarFile.name}")

        outputCallback("Running $jarName...")
        val runCmd = listOf(
            java.absolutePath,
            "-Dfile.encoding=UTF-8",
            "-cp", (listOf(jarFile) + dependencies).joinToString(File.pathSeparator),
            mainClass,
        )
        val runResult = runProcess(runCmd, projectDir)
        outputCallback("Process exited with code ${runResult.exitCode}.")
        Result(runResult.success, if (runResult.success) "Run completed." else "Run failed (exit code ${runResult.exitCode}).")
    }

    private suspend fun runProcess(command: List<String>, workingDir: File): ProcessResult =
        withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(command)
                pb.directory(workingDir)
                pb.redirectErrorStream(true)

                val env = pb.environment()
                env["JAVA_HOME"] = Environment.JAVA_HOME?.absolutePath ?: ""
                env["HOME"] = Environment.HOME?.absolutePath ?: ""
                env["LD_LIBRARY_PATH"] = listOf(
                    Environment.LIB_DIR?.absolutePath,
                    Environment.JAVA_HOME?.let { File(it, "lib").absolutePath },
                ).filterNotNull().joinToString(":")
                env["TMPDIR"] = Environment.TMP_DIR?.absolutePath ?: ""
                env["PATH"] = listOf(
                    Environment.JAVA_HOME?.let { File(it, "bin").absolutePath },
                    Environment.BIN_DIR?.absolutePath,
                    System.getenv("PATH"),
                ).filterNotNull().joinToString(File.pathSeparator)

                val process = pb.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                var line = reader.readLine()
                while (line != null) {
                    outputCallback(line)
                    line = reader.readLine()
                }
                val exitCode = process.waitFor()
                ProcessResult(exitCode, exitCode == 0)
            } catch (e: Exception) {
                log.error("Failed to run command: {}", command, e)
                outputCallback("Error: ${e.message}")
                ProcessResult(-1, false)
            }
        }

    private data class ProcessResult(val exitCode: Int, val success: Boolean)

    fun describeEnvironment(): List<String> {
        val home = Environment.JAVA_HOME
        val kotlinc = kotlincBinary()
        val java = Environment.JAVA

        fun describe(file: File?): String {
            if (file == null) return "missing"
            return "${file.absolutePath} (${if (file.exists()) "found" else "missing"})"
        }

        return listOf(
            "JAVA_HOME: ${home?.absolutePath ?: "missing"}",
            "kotlinc: ${describe(kotlinc)}",
            "java: ${describe(java)}",
        )
    }

    private fun resolveDependencies(projectDir: File): List<File> {
        val jars =
            DependencyStore.resolveJars(DependencyScope.KOTLIN, projectDir) { message ->
                outputCallback("  $message")
            }
        if (jars.isNotEmpty()) {
            outputCallback("Using ${jars.size} Kotlin dependency JAR(s).")
        }
        return jars
    }

    private fun kotlincBinary(): File? {
        val candidates = listOf(
            File(Environment.BIN_DIR, "kotlinc"),
            File(Environment.BIN_DIR, "kotlinc-jvm"),
        )
        for (candidate in candidates) {
            if (candidate.exists()) {
                if (!candidate.canExecute()) candidate.setExecutable(true)
                return candidate
            }
        }
        // Try PATH
        val path = System.getenv("PATH") ?: ""
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            for (name in listOf("kotlinc", "kotlinc-jvm")) {
                val file = File(dir, name)
                if (file.exists() && file.canExecute()) {
                    return file
                } else if (file.exists()) {
                    file.setExecutable(true)
                    return file
                }
            }
        }
        return null
    }

    private fun javaBinary(): File? = jdkBinary("java")

    private fun jarBinary(): File? = jdkBinary("jar")

    private fun jdkBinary(name: String): File? {
        val home = Environment.JAVA_HOME ?: return null
        val file = File(home, "bin/$name")
        if (!file.exists()) return null
        if (!file.canExecute()) file.setExecutable(true)
        return file
    }

    private fun kotlincNotFoundMessage(): String {
        val binDir = Environment.BIN_DIR?.absolutePath ?: "/data/data/com.termux/files/usr/bin"
        return "kotlinc not found in $binDir. Install Kotlin compiler (e.g. via idesetup or package manager) or add it to PATH."
    }

    private fun javacNotFoundMessage(): String {
        val home = Environment.JAVA_HOME
        val location = if (home != null) " (looked in ${home.absolutePath})" else ""
        return "JDK not found$location. Open the terminal and run: pkg install openjdk-17"
    }

    private fun collectKotlinSources(projectDir: File): List<File> {
        val ignored = setOf("build", "out", "bin", ".jcode", ".gradle", ".git", ".idea")
        val result = mutableListOf<File>()
        fun visit(dir: File, depth: Int) {
            if (depth > 20) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name.startsWith(".") || child.name in ignored) continue
                    visit(child, depth + 1)
                } else if (child.extension == "kt") {
                    result.add(child)
                }
            }
        }
        visit(projectDir, 0)
        return result
    }

    private fun findMainClass(file: File): String? {
        return try {
            val source = file.readText(Charsets.UTF_8)
            // Check if it's a top-level main function
            if (MAIN_FUNCTION_REGEX.containsMatchIn(source)) {
                // Look for class/object containing main
                val classMatch = MAIN_IN_CLASS_REGEX.find(source)
                if (classMatch != null) {
                    val className = classMatch.groupValues[1]
                    return qualify(file, source, className)
                }
                // Top-level main -> MainKt
                val simpleName = "${file.nameWithoutExtension}Kt"
                return qualify(file, source, simpleName)
            }
            null
        } catch (e: Exception) {
            log.warn("Failed to read {} for main class detection", file, e)
            null
        }
    }

    private fun qualify(file: File, source: String, simpleName: String): String {
        val pkg = PACKAGE_REGEX.find(source)?.groupValues?.get(1)
        return if (pkg.isNullOrBlank()) simpleName else "$pkg.$simpleName"
    }

    private fun findMainClassInSources(files: List<File>): String? {
        for (file in files) {
            val className = findMainClass(file)
            if (className != null) return className
        }
        return null
    }
}
