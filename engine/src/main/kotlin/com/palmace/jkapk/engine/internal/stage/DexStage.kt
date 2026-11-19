package com.palmace.jkapk.engine.internal.stage

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.android.tools.r8.origin.Origin
import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.Severity
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import java.io.File

// Etapa D8: convierte los .class compilados (Kotlin + Java) a .dex.
// Usa la API programatica de D8 (com.android.tools.r8) sin proceso externo.
//
// Entradas:
//   - programDirs: directorios con .class (workDir/classes/kotlin, /java)
//   - classpathJars: .jar de dependencias (solo resolucion de tipos)
//   - androidJar: library file (framework, necesario para desugaring)
//
// Salida: workDir/dex/classes.dex (y classes2.dex, etc. si multidex).
internal class DexStage : BuildStageExecutor<ResolvedClasspath, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.DEX

    override suspend fun execute(input: ResolvedClasspath, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()

        // Separar jars en program dirs vs classpath jars
        val programDirs = input.jars.filter { it.isDirectory }
        val classpathJars = input.jars.filter { it.isFile && it.extension == "jar" }

        val classFiles = programDirs.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .toList()
        }

        if (classFiles.isEmpty()) {
            ctx.emit(BuildState.Dexing(0, 0))
            ctx.log(LogLevel.INFO, "D8", "Sin .class que dexificar, saltando")
            return input
        }

        val dexDir = File(ctx.workDir, "dex").apply { mkdirs() }

        ctx.emit(BuildState.Dexing(1, 1))
        ctx.log(
            LogLevel.INFO, "D8",
            "Dexificando ${classFiles.size} .class (classpath: ${classpathJars.size} jar, min-api=${ctx.config.minSdk})"
        )

        val diagnostics = mutableListOf<BuildDiagnostic>()
        val handler = object : DiagnosticsHandler {
            override fun error(diagnostic: Diagnostic) {
                val diag = BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = diagnostic.diagnosticMessage.toString(),
                    stage = BuildStage.DEX,
                    rawOutput = diagnostic.toString()
                )
                diagnostics.add(diag)
                ctx.diagnostic(diag)
            }

            override fun warning(diagnostic: Diagnostic) {
                val diag = BuildDiagnostic(
                    severity = Severity.WARNING,
                    message = diagnostic.diagnosticMessage.toString(),
                    stage = BuildStage.DEX
                )
                diagnostics.add(diag)
                ctx.diagnostic(diag)
            }

            override fun info(diagnostic: Diagnostic) {
                // silencioso
            }
        }

        try {
            val builder = D8Command.builder(handler)
                .setMode(CompilationMode.DEBUG)
                .setMinApiLevel(ctx.config.minSdk)
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed)

            // Program data: cada .class individual
            classFiles.forEach { classFile ->
                ctx.checkCancelled()
                builder.addClassProgramData(classFile.readBytes(), Origin.root())
            }

            // Classpath: jars de dependencias (resolucion, no dexificacion)
            if (classpathJars.isNotEmpty()) {
                builder.addClasspathFiles(classpathJars.map { it.toPath() })
            }

            // Library files: android.jar (framework)
            val androidJar = ctx.config.androidJar
            if (androidJar.exists()) {
                builder.addLibraryFiles(androidJar.toPath())
            } else {
                ctx.log(
                    LogLevel.WARNING, "D8",
                    "android.jar no existe en ${androidJar.absolutePath}, " +
                        "el desugaring puede fallar"
                )
            }

            D8.run(builder.build())
        } catch (t: Throwable) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "D8 fallo: ${t.message}",
                stage = BuildStage.DEX,
                rawOutput = t.stackTraceToString()
            )
            diagnostics.add(diag)
            ctx.diagnostic(diag)
            throw DexExecutionException("D8 fallo", diag)
        }

        val dexFiles = dexDir.listFiles { f -> f.extension == "dex" }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (dexFiles.isEmpty()) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "D8 no genero ningun .dex en ${dexDir.absolutePath}",
                stage = BuildStage.DEX,
                rawOutput = diagnostics.joinToString("\n") { it.message }
            )
            ctx.diagnostic(diag)
            throw DexExecutionException("D8 sin output", diag)
        }

        ctx.log(LogLevel.INFO, "D8", "Generados ${dexFiles.size} .dex: ${dexFiles.joinToString { it.name }}")
        ctx.emit(BuildState.Dexing(dexFiles.size, dexFiles.size))

        return input.copy(dexFiles = dexFiles)
    }
}

internal class DexExecutionException(
    message: String,
    val diagnostic: BuildDiagnostic
) : Exception(message)
