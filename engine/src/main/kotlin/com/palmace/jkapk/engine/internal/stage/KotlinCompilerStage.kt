package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.Severity
import com.palmace.jkapk.engine.SourceRef
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

// Compila fuentes Kotlin a workDir/classes/kotlin/.
// Pasa tambien los .java del proyecto para resolver referencias cruzadas.
// Devuelve ResolvedClasspath con outputDir agregado a jars, para que javac lo vea.
internal class KotlinCompilerStage : BuildStageExecutor<ResolvedClasspath, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.KOTLIN

    override suspend fun execute(input: ResolvedClasspath, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()

        val kotlinFiles = collectFiles(ctx.config.kotlinSourceDirs, "kt")
        val javaFilesForSymbols = collectFiles(ctx.config.javaSourceDirs, "java")

        if (kotlinFiles.isEmpty()) {
            ctx.emit(BuildState.CompilingKotlin("(sin archivos)", 0, 0))
            ctx.log(LogLevel.INFO, "KotlinCompiler", "Sin fuentes Kotlin, saltando")
            return input
        }

        val outputDir = File(ctx.workDir, "classes/kotlin").apply { mkdirs() }

        val classpath = input.jars.joinToString(File.pathSeparator) { it.absolutePath }
        val allSources = kotlinFiles + javaFilesForSymbols

        val args = arrayOf(
            "-d", outputDir.absolutePath,
            "-classpath", classpath,
            "-no-stdlib",
            "-no-reflect",
            "-jvm-target", "17",
            *allSources.map { it.absolutePath }.toTypedArray()
        )

        ctx.emit(BuildState.CompilingKotlin(kotlinFiles.first().name, 0, kotlinFiles.size))
        ctx.log(
            LogLevel.INFO, "KotlinCompiler",
            "Compilando ${kotlinFiles.size} .kt (+${javaFilesForSymbols.size} .java para simbolos)"
        )

        val buffer = ByteArrayOutputStream()
        val errStream = PrintStream(buffer, true, "UTF-8")

        val exitCode = try {
            K2JVMCompiler().exec(errStream, *args)
        } catch (t: Throwable) {
            errStream.flush()
            val raw = buffer.toString("UTF-8")
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "Excepcion del compilador Kotlin: ${t.message}",
                stage = BuildStage.KOTLIN,
                rawOutput = "$raw\n${t.stackTraceToString()}"
            )
            ctx.diagnostic(diag)
            throw KotlinCompilationException("Kotlin fallo con excepcion", listOf(diag))
        } finally {
            errStream.flush()
        }

        val rawOutput = buffer.toString("UTF-8")
        val diagnostics = KotlinOutputParser.parse(rawOutput, ctx)
        diagnostics.forEach { ctx.diagnostic(it) }

        val errorCount = diagnostics.count { it.severity == Severity.ERROR }

        if (exitCode.code != 0 || errorCount > 0) {
            throw KotlinCompilationException(
                "Kotlin fallo con $errorCount error(es)",
                diagnostics
            )
        }

        ctx.emit(BuildState.CompilingKotlin("(completado)", kotlinFiles.size, kotlinFiles.size))

        return input.copy(jars = input.jars + outputDir)
    }

    private fun collectFiles(dirs: List<File>, ext: String): List<File> {
        return dirs.filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
                    .toList()
            }
            .distinct()
    }
}

// Parser minimo de salida de kotlinc. Formato:
//   ruta.kt:line:col: error: mensaje
//   ruta.kt:line:col: warning: mensaje
internal object KotlinOutputParser {

    private val LINE_REGEX = Regex("""^(.+?\.kt):(\d+):(\d+): (error|warning): (.+)$""")
    private val FILE_ONLY_REGEX = Regex("""^(error|warning): (.+)$""")

    fun parse(raw: String, ctx: BuildContext): List<BuildDiagnostic> {
        val result = mutableListOf<BuildDiagnostic>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            val match = LINE_REGEX.find(trimmed)
            if (match != null) {
                val (path, lineStr, colStr, sevStr, msg) = match.destructured
                val severity = if (sevStr == "error") Severity.ERROR else Severity.WARNING
                result.add(
                    BuildDiagnostic(
                        severity = severity,
                        message = msg,
                        source = buildSourceRef(path, lineStr, colStr, ctx),
                        stage = BuildStage.KOTLIN
                    )
                )
                return@forEach
            }

            val fileOnly = FILE_ONLY_REGEX.find(trimmed)
            if (fileOnly != null) {
                val (sevStr, msg) = fileOnly.destructured
                val severity = if (sevStr == "error") Severity.ERROR else Severity.WARNING
                result.add(
                    BuildDiagnostic(
                        severity = severity,
                        message = msg,
                        stage = BuildStage.KOTLIN
                    )
                )
            }
        }

        return result
    }

    private fun buildSourceRef(path: String, line: String, col: String, ctx: BuildContext): SourceRef {
        val abs = File(path)
        val projectRoot = ctx.config.projectRoot
        return if (abs.startsWith(projectRoot)) {
            SourceRef(
                projectRoot = projectRoot,
                relativePath = abs.relativeTo(projectRoot).path,
                line = line.toIntOrNull(),
                column = col.toIntOrNull()
            )
        } else {
            SourceRef(
                projectRoot = ctx.workDir,
                relativePath = abs.relativeToOrSelf(ctx.workDir).path,
                line = line.toIntOrNull(),
                column = col.toIntOrNull()
            )
        }
    }
}

internal class KotlinCompilationException(
    message: String,
    val diagnostics: List<BuildDiagnostic>
) : Exception(message)
