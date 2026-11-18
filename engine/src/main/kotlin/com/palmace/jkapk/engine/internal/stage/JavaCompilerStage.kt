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
import java.io.File
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

// Compila fuentes Java a workDir/classes/java/ usando javax.tools.
// Requiere JDK completo (Termux/CI). En Android runtime, ToolProvider
// devuelve null y se lanza JavaToolingUnavailableException con un
// BuildDiagnostic claro. La version Android usara nb-javac en Fase 4c.
internal class JavaCompilerStage : BuildStageExecutor<ResolvedClasspath, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.JAVA

    override suspend fun execute(input: ResolvedClasspath, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()

        val javaFiles = collectFiles(ctx.config.javaSourceDirs, "java")
        if (javaFiles.isEmpty()) {
            ctx.emit(BuildState.CompilingJava("(sin archivos)", 0, 0))
            ctx.log(LogLevel.INFO, "JavaCompiler", "Sin fuentes Java, saltando")
            return input
        }

        val compiler = ToolProvider.getSystemJavaCompiler()
        if (compiler == null) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "javax.tools.JavaCompiler no disponible. " +
                    "Requiere JDK completo (Termux/CI). " +
                    "En Android runtime se usara nb-javac (Fase 4c).",
                stage = BuildStage.JAVA
            )
            ctx.diagnostic(diag)
            throw JavaToolingUnavailableException(diag)
        }

        val outputDir = File(ctx.workDir, "classes/java").apply { mkdirs() }
        val diagnosticCollector = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(
            diagnosticCollector,
            null,
            Charsets.UTF_8
        )

        try {
            val units = fileManager.getJavaFileObjectsFromFiles(javaFiles)
            val classpath = input.jars.joinToString(File.pathSeparator) { it.absolutePath }

            val options = listOf(
                "-d", outputDir.absolutePath,
                "-classpath", classpath,
                "-source", "17",
                "-target", "17",
                "-encoding", "UTF-8"
            )

            ctx.emit(BuildState.CompilingJava(javaFiles.first().name, 0, javaFiles.size))
            ctx.log(
                LogLevel.INFO, "JavaCompiler",
                "Compilando ${javaFiles.size} .java con classpath de ${input.jars.size} jar(s)"
            )

            val task = compiler.getTask(null, fileManager, diagnosticCollector, options, null, units)
            val success = task.call()

            val diagnostics = diagnosticCollector.diagnostics.map { it.toBuildDiagnostic(ctx) }
            diagnostics.forEach { ctx.diagnostic(it) }

            val errorCount = diagnostics.count { it.severity == Severity.ERROR }

            if (!success || errorCount > 0) {
                throw JavaCompilationException(
                    "Java fallo con $errorCount error(es)",
                    diagnostics
                )
            }

            ctx.emit(BuildState.CompilingJava("(completado)", javaFiles.size, javaFiles.size))

            return input.copy(jars = input.jars + outputDir)
        } finally {
            try {
                fileManager.close()
            } catch (_: Throwable) {
                // ignorar
            }
        }
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

    private fun Diagnostic<out JavaFileObject>.toBuildDiagnostic(ctx: BuildContext): BuildDiagnostic {
        val mappedSeverity = when (kind) {
            Diagnostic.Kind.ERROR -> Severity.ERROR
            Diagnostic.Kind.WARNING, Diagnostic.Kind.MANDATORY_WARNING -> Severity.WARNING
            else -> Severity.INFO
        }

        val sourceRef = source?.toUri()?.path?.let { rawPath ->
            val abs = File(rawPath)
            val projectRoot = ctx.config.projectRoot
            if (abs.startsWith(projectRoot)) {
                SourceRef(
                    projectRoot = projectRoot,
                    relativePath = abs.relativeTo(projectRoot).path,
                    line = lineNumber.takeIf { it > 0 }?.toInt(),
                    column = columnNumber.takeIf { it > 0 }?.toInt()
                )
            } else {
                SourceRef(
                    projectRoot = ctx.workDir,
                    relativePath = abs.relativeToOrSelf(ctx.workDir).path,
                    line = lineNumber.takeIf { it > 0 }?.toInt(),
                    column = columnNumber.takeIf { it > 0 }?.toInt()
                )
            }
        }

        return BuildDiagnostic(
            severity = mappedSeverity,
            message = getMessage(null),
            source = sourceRef,
            stage = BuildStage.JAVA,
            rawOutput = toString()
        )
    }
}

internal class JavaCompilationException(
    message: String,
    val diagnostics: List<BuildDiagnostic>
) : Exception(message)

internal class JavaToolingUnavailableException(
    val diagnostic: BuildDiagnostic
) : Exception(diagnostic.message)
