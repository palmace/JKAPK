package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.Severity
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import java.io.File
import java.util.concurrent.TimeUnit

// Etapa AAPT2: compila recursos y los enlaza en resources.ap_.
// Tambien genera R.java en workDir/generated/source/r/<package>/R.java.
//
// Deteccion del binario aapt2 (en orden, cada uno validado con 'aapt2 version'):
//   1. ProjectConfig.aapt2Binary
//   2. ctx.nativeLibDir/aapt2 o ctx.nativeLibDir/libaapt2.so (Android jniLibs)
//   3. $ANDROID_HOME/build-tools/<latest>/aapt2 (CI)
//   4. 'aapt2' del PATH (Termux con paquete)
//
// Si ninguno pasa, lanza Aapt2UnavailableException con diagnostico claro.
internal class Aapt2Stage : BuildStageExecutor<ResolvedClasspath, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.RESOURCES

    override suspend fun execute(input: ResolvedClasspath, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()

        val resDir = ctx.config.resourceDir
        val hasLocalRes = resDir != null && resDir.isDirectory
        val hasAarRes = input.aarResources.any { it.isDirectory }

        if (!hasLocalRes && !hasAarRes) {
            ctx.emit(BuildState.ProcessingResources("(sin recursos)"))
            ctx.log(LogLevel.INFO, "AAPT2", "Sin recursos, saltando")
            return input
        }

        val aapt2 = resolveAapt2(ctx)
        if (aapt2 == null) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "No se encontro un binario aapt2 funcional. " +
                    "Rutas comprobadas: ProjectConfig.aapt2Binary, nativeLibDir, " +
                    "\$ANDROID_HOME/build-tools/*/aapt2, PATH. " +
                    "Verifica con 'aapt2 version'.",
                stage = BuildStage.RESOURCES
            )
            ctx.diagnostic(diag)
            throw Aapt2UnavailableException(diag)
        }

        ctx.log(LogLevel.INFO, "AAPT2", "Usando aapt2: ${aapt2.absolutePath}")

        val compiledResDir = File(ctx.workDir, "res-compiled").apply { mkdirs() }
        val generatedJavaDir = File(ctx.workDir, "generated/source/r").apply { mkdirs() }
        val resourcesApk = File(ctx.workDir, "resources.ap_")

        // Paso 1: aapt2 compile --dir <res> -o <zip>
        ctx.emit(BuildState.ProcessingResources("Compilando recursos"))
        val compileZip = File(compiledResDir, "compiled.zip")

        if (hasLocalRes) {
            val compileArgs = mutableListOf(
                aapt2.absolutePath,
                "compile",
                "--dir", resDir!!.absolutePath,
                "-o", compileZip.absolutePath
            )
            runAapt2(compileArgs, ctx, "compile")
        }

        // Recopilar los .flat de AARs (si los hay) para el link
        val aarFlatZips = mutableListOf<File>()
        input.aarResources.forEachIndexed { index, aarRes ->
            ctx.checkCancelled()
            val aarZip = File(compiledResDir, "aar-$index.zip")
            ctx.emit(BuildState.ProcessingResources("Compilando recursos de AAR ${index + 1}/${input.aarResources.size}"))
            val aarArgs = mutableListOf(
                aapt2.absolutePath,
                "compile",
                "--dir", aarRes.absolutePath,
                "-o", aarZip.absolutePath
            )
            runAapt2(aarArgs, ctx, "compile (aar)")
            aarFlatZips.add(aarZip)
        }

        // Paso 2: aapt2 link
        ctx.emit(BuildState.ProcessingResources("Enlazando recursos"))
        val androidJar = ctx.config.androidJar
        if (!androidJar.exists()) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "android.jar no existe en ${androidJar.absolutePath}. " +
                    "Configura ProjectConfig.androidJar con la ruta al jar del SDK " +
                    "(p.ej. \$ANDROID_HOME/platforms/android-<compileSdk>/android.jar).",
                stage = BuildStage.RESOURCES
            )
            ctx.diagnostic(diag)
            throw Aapt2UnavailableException(diag)
        }

        val linkArgs = mutableListOf(
            aapt2.absolutePath,
            "link",
            "-o", resourcesApk.absolutePath,
            "--manifest", ctx.config.manifestFile.absolutePath,
            "-I", androidJar.absolutePath,
            "--java", generatedJavaDir.absolutePath,
            "--auto-add-overlay"
        )

        if (compileZip.exists()) {
            linkArgs.add("-R")
            linkArgs.add(compileZip.absolutePath)
        }
        aarFlatZips.filter { it.exists() }.forEach { zip ->
            linkArgs.add("-R")
            linkArgs.add(zip.absolutePath)
        }

        runAapt2(linkArgs, ctx, "link")

        ctx.emit(BuildState.ProcessingResources("(completado)"))
        ctx.log(LogLevel.INFO, "AAPT2", "resources.ap_ y R.java generados")

        return input.copy(
            generatedJavaDir = generatedJavaDir,
            compiledResourcesApk = resourcesApk
        )
    }

    private fun resolveAapt2(ctx: BuildContext): File? {
        val candidates = mutableListOf<File>()

        // 1. ProjectConfig.aapt2Binary
        ctx.config.aapt2Binary?.let { candidates.add(it) }

        // 2. nativeLibDir
        candidates.add(File(ctx.nativeLibDir, "aapt2"))
        candidates.add(File(ctx.nativeLibDir, "libaapt2.so"))

        // 3. $ANDROID_HOME/build-tools/<latest>/aapt2
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (androidHome != null) {
            val buildTools = File(androidHome, "build-tools")
            if (buildTools.isDirectory) {
                buildTools.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.forEach { v -> candidates.add(File(v, "aapt2")) }
            }
        }

        // 4. PATH
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { p ->
            candidates.add(File(p, "aapt2"))
        }

        return candidates.firstOrNull { it.exists() && isExecutable(it) && passesVersionCheck(it) }
    }

    private fun isExecutable(file: File): Boolean = file.canExecute() || file.setExecutable(true)

    private fun passesVersionCheck(aapt2: File): Boolean {
        return try {
            val process = ProcessBuilder(aapt2.absolutePath, "version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun runAapt2(args: List<String>, ctx: BuildContext, phaseLabel: String) {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()

        if (exit != 0) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "aapt2 $phaseLabel fallo (exit=$exit):\n$output",
                stage = BuildStage.RESOURCES,
                rawOutput = output
            )
            ctx.diagnostic(diag)
            throw Aapt2ExecutionException("aapt2 $phaseLabel fallo", diag)
        }
    }
}

internal class Aapt2UnavailableException(
    val diagnostic: BuildDiagnostic
) : Exception(diagnostic.message)

internal class Aapt2ExecutionException(
    message: String,
    val diagnostic: BuildDiagnostic
) : Exception(message)
