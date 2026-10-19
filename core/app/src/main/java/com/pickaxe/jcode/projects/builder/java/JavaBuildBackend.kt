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
package com.pickaxe.jcode.projects.builder.java

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
 * Builds and runs Java projects that do not use Gradle.
 *
 * This backend handles two project types:
 *  - [BuildSystem.JAVA_SINGLE] — a single `.java` file is compiled and run directly.
 *  - [BuildSystem.JAVA_JAR] — all `.java` sources are compiled, packaged into a JAR, and run.
 *
 * It uses the `javac`, `java` and `jar` binaries from [Environment.JAVA_HOME], so a JDK must be
 * installed in the Termux environment (`pkg install openjdk-17`).
 *
 * Build output is streamed line-by-line to [outputCallback] so the IDE's build output panel can
 * display it in real time, just like the Gradle Tooling API output.
 *
 * @param outputCallback Called on the main thread for each line of process output (stdout + stderr).
 */
class JavaBuildBackend(
    private val outputCallback: (String) -> Unit,
) {

    companion object {
        private val log = LoggerFactory.getLogger(JavaBuildBackend::class.java)

        /** Matches a `package` declaration. */
        private val PACKAGE_REGEX = Regex("^\\s*package\\s+([\\w.]+)\\s*;", RegexOption.MULTILINE)

        /** Regex that finds a class declaration followed by a `main` method. */
        private val MAIN_METHOD_REGEX =
            Regex(
                "(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+(\\w+)" +
                    "[^{]*\\{.*?public\\s+static\\s+void\\s+main\\s*\\(\\s*String",
                RegexOption.DOT_MATCHES_ALL,
            )

        /** A simpler pattern that just looks for the main method signature on its own. */
        private val MAIN_SIGNATURE_REGEX =
            Regex("public\\s+static\\s+void\\s+main\\s*\\(\\s*String")
    }

    /** Result of a build/run operation. */
    data class Result(val success: Boolean, val message: String)

    /**
     * Compiles and runs the project in [projectDir].
     *
     * Detects the build system and delegates to [runSingleFile] or [runJarProject].
     * Returns a [Result] indicating success or failure.
     *
     * @param ignoreGradle When `true`, a project that is detected as a Gradle project is still
     *   built as a plain Java project. This is used as a fallback when the Gradle Tooling API is not
     *   available (for example when the project was created from the "Java Application" template and
     *   only contains plain Java sources).
     */
    suspend fun buildAndRun(projectDir: File, ignoreGradle: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val buildSystem = BuildSystemDetector.detect(projectDir)
            if (buildSystem == null) {
                return@withContext Result(
                    false,
                    "Not a recognizable project: ${projectDir.absolutePath}",
                )
            }

            when (buildSystem) {
                BuildSystem.JAVA_SINGLE -> runSingleFile(projectDir)
                BuildSystem.JAVA_JAR -> runJarProject(projectDir)
                BuildSystem.GRADLE ->
                    if (ignoreGradle) {
                        outputCallback(
                            "Gradle Tooling API is not available. Building plain Java sources directly..."
                        )
                        runJarProject(projectDir)
                    } else {
                        Result(
                            false,
                            "This is a Gradle project — use the Gradle build actions instead.",
                        )
                    }
            }
        }

    /**
     * Compiles a single `.java` file and runs its main class.
     */
    private suspend fun runSingleFile(projectDir: File): Result = withContext(Dispatchers.IO) {
        val javaFile = projectDir.listFiles { it.extension == "java" }?.firstOrNull()
            ?: collectJavaSources(projectDir).firstOrNull()
            ?: return@withContext Result(false, "No .java file found in ${projectDir.name}")

        val javac = javacBinary() ?: return@withContext Result(false, javacNotFoundMessage())
        val java = javaBinary() ?: return@withContext Result(false, javacNotFoundMessage())

        val outputDir = File(projectDir, ".jcode/classes")
        outputDir.mkdirs()

        val mainClass = findMainClass(javaFile) ?: javaFile.nameWithoutExtension

        outputCallback("Compiling ${javaFile.name}...")
        val compileCmd = listOf(
            javac.absolutePath,
            "-encoding", "UTF-8",
            "-d", outputDir.absolutePath,
            javaFile.absolutePath,
        )
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
            "-cp", outputDir.absolutePath,
            mainClass,
        )
        val runResult = runProcess(runCmd, projectDir)
        outputCallback("Process exited with code ${runResult.exitCode}.")
        Result(runResult.success, if (runResult.success) "Run completed." else "Run failed (exit code ${runResult.exitCode}).")
    }

    /**
     * Compiles all `.java` sources, packages them into a JAR, and runs the JAR.
     */
    private suspend fun runJarProject(projectDir: File): Result = withContext(Dispatchers.IO) {
        val javac = javacBinary() ?: return@withContext Result(false, javacNotFoundMessage())
        val java = javaBinary() ?: return@withContext Result(false, javacNotFoundMessage())
        val jar = jarBinary() ?: return@withContext Result(false, javacNotFoundMessage())

        val sourceFiles = collectJavaSources(projectDir)
        if (sourceFiles.isEmpty()) {
            return@withContext Result(false, "No .java source files found.")
        }

        val outputDir = File(projectDir, ".jcode/classes")
        outputDir.mkdirs()
        val buildDir = File(projectDir, ".jcode/build")
        buildDir.mkdirs()

        // Find main class before compiling
        val mainClass = findMainClassInSources(sourceFiles) ?: run {
            outputCallback("No main class found (no 'public static void main' method).")
            return@withContext Result(false, "No main class found.")
        }
        outputCallback("Detected main class: $mainClass")

        // Compile
        outputCallback("Compiling ${sourceFiles.size} source file(s)...")
        val compileCmd = mutableListOf(
            javac.absolutePath,
            "-encoding", "UTF-8",
            "-d", outputDir.absolutePath,
        )
        // Add source root as sourcepath so inner classes resolve
        compileCmd.addAll(sourceFiles.map { it.absolutePath })
        val compileResult = runProcess(compileCmd, projectDir)
        if (!compileResult.success) {
            outputCallback("Compilation failed.")
            return@withContext Result(false, "Compilation failed (exit code ${compileResult.exitCode})")
        }
        outputCallback("Compilation successful.")

        // Create manifest
        val manifestFile = File(buildDir, "MANIFEST.MF")
        manifestFile.writeText("Manifest-Version: 1.0\nMain-Class: $mainClass\n\n")

        // Package JAR
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

        // Run
        outputCallback("Running $jarName...")
        val runCmd = listOf(
            java.absolutePath,
            "-Dfile.encoding=UTF-8",
            "-jar", jarFile.absolutePath,
        )
        val runResult = runProcess(runCmd, projectDir)
        outputCallback("Process exited with code ${runResult.exitCode}.")
        Result(runResult.success, if (runResult.success) "Run completed." else "Run failed (exit code ${runResult.exitCode}).")
    }

    // ─── Process execution ────────────────────────────────────────────────

    /** Runs a command and streams its stdout+stderr line-by-line to [outputCallback]. */
    private suspend fun runProcess(command: List<String>, workingDir: File): ProcessResult =
        withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(command)
                pb.directory(workingDir)
                pb.redirectErrorStream(true)

                // Inherit the Termux environment so the JDK can find its libraries
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

    // ─── JDK binary resolution ────────────────────────────────────────────

    private fun javacBinary(): File? = jdkBinary("javac")

    private fun javaBinary(): File? = jdkBinary("java")

    private fun jarBinary(): File? = jdkBinary("jar")

    /**
     * Resolves a JDK tool from `$JAVA_HOME/bin`. The executable bit is restored if it was lost when
     * the JDK was extracted.
     */
    private fun jdkBinary(name: String): File? {
        val home = Environment.JAVA_HOME ?: return null
        val file = File(home, "bin/$name")
        if (!file.exists()) return null
        if (!file.canExecute()) file.setExecutable(true)
        return file
    }

    private fun javacNotFoundMessage(): String {
        val home = Environment.JAVA_HOME
        val location = if (home != null) " (looked in ${home.absolutePath})" else ""
        return "JDK not found$location. Open the terminal and run: pkg install openjdk-17"
    }

    // ─── Source scanning ──────────────────────────────────────────────────

    /** Collects all `.java` files under [projectDir], excluding generated and VCS directories. */
    private fun collectJavaSources(projectDir: File): List<File> {
        val ignored = setOf("build", "out", "bin", ".jcode", ".gradle", ".git", ".idea")
        val result = mutableListOf<File>()
        fun visit(dir: File, depth: Int) {
            if (depth > 20) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name.startsWith(".") || child.name in ignored) continue
                    visit(child, depth + 1)
                } else if (child.extension == "java") {
                    result.add(child)
                }
            }
        }
        visit(projectDir, 0)
        return result
    }

    /**
     * Scans [file] for a class containing a `main` method and returns its fully qualified name
     * (including the `package` declaration, if any).
     */
    private fun findMainClass(file: File): String? {
        return try {
            val source = file.readText(Charsets.UTF_8)
            val simpleName =
                MAIN_METHOD_REGEX.find(source)?.groupValues?.get(1)
                    ?: if (MAIN_SIGNATURE_REGEX.containsMatchIn(source)) {
                        file.nameWithoutExtension
                    } else {
                        null
                    }
            simpleName?.let { simple -> qualify(file, source, simple) }
        } catch (e: Exception) {
            log.warn("Failed to read {} for main class detection", file, e)
            null
        }
    }

    /** Prepends the file's `package` declaration to [simpleName], if one is present. */
    private fun qualify(file: File, source: String, simpleName: String): String {
        val pkg = PACKAGE_REGEX.find(source)?.groupValues?.get(1)
        return if (pkg.isNullOrBlank()) simpleName else "$pkg.$simpleName"
    }

    /** Scans all [files] for a class containing a `main` method. */
    private fun findMainClassInSources(files: List<File>): String? {
        for (file in files) {
            val className = findMainClass(file)
            if (className != null) return className
        }
        return null
    }
}
