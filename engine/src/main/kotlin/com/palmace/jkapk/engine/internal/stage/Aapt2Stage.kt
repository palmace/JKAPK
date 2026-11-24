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
import java.util.zip.ZipFile

// Etapa AAPT2: compila recursos y los enlaza en resources.ap_.
// Tambien genera R.java en workDir/generated/source/r/<package>/R.java.
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
            val trace = consumeCandidateTrace()
            val detailSuffix = if (trace.isNotBlank()) " | Candidatos: $trace" else ""
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "No se encontro un binario aapt2 funcional. " +
                    "Rutas comprobadas: ProjectConfig.aapt2Binary, nativeLibDir, " +
                    "\$ANDROID_HOME/build-tools/*/aapt2, PATH. " +
                    "Verifica con 'aapt2 version'.$detailSuffix",
                stage = BuildStage.RESOURCES
            )
            ctx.diagnostic(diag)
            throw Aapt2UnavailableException(diag)
        }
        ctx.log(LogLevel.INFO, "AAPT2", "Usando aapt2: ${aapt2.absolutePath}")

        val compiledResDir = File(ctx.workDir, "res-compiled").apply { mkdirs() }
        val generatedJavaDir = File(ctx.workDir, "generated/source/r").apply { mkdirs() }
        val resourcesApk = File(ctx.workDir, "resources.ap_")

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
        lastCandidateTrace.get()?.clear()
        val candidates = mutableListOf<File>()

        // 1. nativeLibDir / libaapt2.so (estilo CodeAssist Omni Aapt2Jni)
        candidates.add(File(ctx.nativeLibDir, "libaapt2.so"))
        candidates.add(File(ctx.nativeLibDir, "aapt2"))

        // 2. ProjectConfig.aapt2Binary
        ctx.config.aapt2Binary?.let { candidates.add(it) }

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

        // 4. PATH / Termux
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { p ->
            candidates.add(File(p, "aapt2"))
        }
        candidates.add(File("/data/data/com.termux/files/usr/bin/aapt2"))

        val distinctCandidates = candidates.distinctBy { it.absolutePath }
        for (cand in distinctCandidates) {
            if (!cand.exists()) {
                recordCandidateReason(ctx, cand, "no existe")
                continue
            }
            ctx.log(LogLevel.INFO, "AAPT2", "Probando candidato: ${cand.absolutePath} (canExec=${cand.canExecute()})")
            val execCand = try {
                prepareExecutableForAndroid(cand, ctx)
            } catch (t: Throwable) {
                recordCandidateReason(ctx, cand, "error preparando (${t.javaClass.simpleName}: ${t.message})")
                null
            }
            if (execCand == null) {
                recordCandidateReason(ctx, cand, "prepareExecutableForAndroid devolvio null (canExec=${cand.canExecute()})")
                continue
            }
            if (passesVersionCheck(execCand, ctx.nativeLibDir, ctx)) {
                recordCandidateReason(ctx, cand, "OK (${execCand.absolutePath})")
                return execCand
            } else {
                recordCandidateReason(ctx, cand, "fallo passesVersionCheck en ${execCand.absolutePath}")
            }
        }

        // Fallback estilo Aapt2Jni.extractBinaryFromApk si no existía el archivo previo
        val extracted = try {
            rescueFromApkOrFallback(ctx)
        } catch (t: Throwable) {
            recordCandidateReason(ctx, File(ctx.nativeLibDir, "libaapt2.so"), "error en rescueFromApkOrFallback (${t.javaClass.simpleName}: ${t.message})")
            null
        }
        if (extracted != null) {
            if (passesVersionCheck(extracted, ctx.nativeLibDir, ctx)) {
                recordCandidateReason(ctx, extracted, "OK via rescueFromApkOrFallback")
                return extracted
            } else {
                recordCandidateReason(ctx, extracted, "rescatado fallo passesVersionCheck")
            }
        } else {
            recordCandidateReason(ctx, File(ctx.nativeLibDir, "libaapt2.so"), "rescueFromApkOrFallback devolvio null")
        }

        return null
    }

    private fun resolveWritableAppDataDir(ctx: BuildContext): File? {
        val candidates = listOfNotNull(
            ctx.cacheDir.takeIf {
                val p = it.absolutePath
                !p.startsWith("/data/app") && !p.startsWith("/sdcard") && !p.startsWith("/storage/emulated")
            }?.parentFile,
            ctx.workDir.takeIf {
                val p = it.absolutePath
                p.startsWith("/data/data/") || p.startsWith("/data/user/")
            }?.let { dir ->
                var cur: File? = dir
                while (cur != null && cur.name !in setOf("files", "cache", "code_cache")) {
                    cur = cur.parentFile
                }
                cur?.parentFile
            },
            ctx.nativeLibDir.parentFile?.parentFile?.takeIf { !it.absolutePath.startsWith("/data/app") }
        )
        return candidates.firstOrNull { it.exists() || it.mkdirs() }
    }

    private fun prepareExecutableForAndroid(source: File, ctx: BuildContext): File? {
        val inReadOnlyAppLib = source.absolutePath.startsWith("/data/app/")
        if (!inReadOnlyAppLib && source.canExecute() && passesVersionCheck(source, ctx.nativeLibDir, ctx)) {
            return source
        }

        val appDataDir = resolveWritableAppDataDir(ctx)
        val targetDirs = (
            listOfNotNull(appDataDir?.let { File(it, "files/bin") }) +
                ctx.config.internalBinDirs +
                listOfNotNull(
                    appDataDir?.let { File(it, "files/jkapk-bin") },
                    appDataDir?.let { File(it, "code_cache/jkapk-bin") },
                    ctx.cacheDir.takeIf {
                        !it.absolutePath.startsWith("/sdcard") && !it.absolutePath.startsWith("/storage/emulated")
                    }?.let { File(it, "bin") }
                )
            ).distinctBy { it.absolutePath }

        for (binDir in targetDirs) {
            try {
                binDir.mkdirs()
                if (!binDir.isDirectory) continue
                val target = File(binDir, "aapt2_sovereign")
                if (target.exists()) {
                    target.setWritable(true, false)
                }
                if (!target.exists() || target.length() != source.length()) {
                    source.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                ensureBundledSupportLibs(binDir, ctx)
                applyZenithSeal(target, ctx)
                if (target.exists() && target.canExecute()) {
                    ctx.log(LogLevel.INFO, "AAPT2", "Binario preparado en ${target.absolutePath}")
                    return target
                }
            } catch (t: Throwable) {
                ctx.log(LogLevel.WARNING, "AAPT2", "Fallo preparando copia en ${binDir.absolutePath}: ${t.message}")
            }
        }

        source.setExecutable(true, false)
        return if (source.canExecute()) source else null
    }

    private fun rescueFromApkOrFallback(ctx: BuildContext): File? {
        val appDataDir = resolveWritableAppDataDir(ctx) ?: return null
        val target = File(appDataDir, "files/bin/aapt2_sovereign")
        return try {
            target.parentFile?.mkdirs()
            if (target.exists()) {
                target.setWritable(true, false)
            }
            val bundled = File(ctx.nativeLibDir, "libaapt2.so")
            if (bundled.exists()) {
                bundled.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                val apkFile = listOfNotNull(
                    appDataDir.let { File(it, "base.apk") },
                    File("/data/app").walkTopDown().maxDepth(3).firstOrNull {
                        it.name == "base.apk" && it.absolutePath.contains("jkapk")
                    }
                ).firstOrNull { it.exists() } ?: return null

                val abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
                ZipFile(apkFile).use { zip ->
                    val entry = abis.asSequence()
                        .mapNotNull { abi -> zip.getEntry("lib/$abi/libaapt2.so") }
                        .firstOrNull() ?: return null
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            applyZenithSeal(target, ctx)
            if (target.exists() && target.canExecute()) target else null
        } catch (t: Throwable) {
            ctx.log(LogLevel.WARNING, "AAPT2", "Fallo en rescueFromApkOrFallback: ${t.message}")
            null
        }
    }

    private fun applyZenithSeal(target: File, ctx: BuildContext) {
        target.setExecutable(true, false)
        target.setWritable(false, false)
        target.setReadOnly()
        try {
            val exit = Runtime.getRuntime().exec(arrayOf("chmod", "0444", target.absolutePath)).waitFor()
            if (exit != 0) {
                Runtime.getRuntime().exec(arrayOf("chmod", "0755", target.absolutePath)).waitFor()
            }
        } catch (t: Throwable) {
            ctx.log(LogLevel.WARNING, "AAPT2", "No se pudo aplicar chmod en ${target.absolutePath}: ${t.message}")
        }
    }

    private fun withNativeLibraryPath(pb: ProcessBuilder, nativeLibDir: File, binDir: File? = null): ProcessBuilder {
        val env = pb.environment()
        val paths = mutableListOf<String>()
        if (nativeLibDir.path.isNotBlank()) paths.add(nativeLibDir.absolutePath)
        if (binDir != null && binDir.path.isNotBlank() && binDir.absolutePath != nativeLibDir.absolutePath) {
            paths.add(binDir.absolutePath)
        }
        val current = env["LD_LIBRARY_PATH"]
        if (!current.isNullOrBlank()) {
            current.split(File.pathSeparator).forEach { p ->
                if (p.isNotBlank() && p !in paths) paths.add(p)
            }
        }
        env["LD_LIBRARY_PATH"] = paths.joinToString(File.pathSeparator)
        return pb
    }

    private fun passesVersionCheck(aapt2: File, nativeLibDir: File, ctx: BuildContext? = null): Boolean {
        return try {
            val process = withNativeLibraryPath(
                ProcessBuilder(aapt2.absolutePath, "version"),
                nativeLibDir,
                aapt2.parentFile
            ).redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val msg = "Timeout (5s) en '${aapt2.absolutePath} version'"
                ctx?.log(LogLevel.WARNING, "AAPT2", msg)
                recordCandidateReason(ctx, aapt2, msg)
                return false
            }
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val compactOut = out.replace("\n", " / ").take(240)
                val msg = "exit=$exitCode en '${aapt2.absolutePath}': $compactOut"
                ctx?.log(LogLevel.WARNING, "AAPT2", msg)
                recordCandidateReason(ctx, aapt2, msg)
                return false
            }
            true
        } catch (t: Throwable) {
            val msg = "Excepcion (${t.javaClass.simpleName}: ${t.message}) en '${aapt2.absolutePath}'"
            ctx?.log(LogLevel.WARNING, "AAPT2", msg)
            recordCandidateReason(ctx, aapt2, msg)
            false
        }
    }
    private fun runAapt2(args: List<String>, ctx: BuildContext, phaseLabel: String) {
        val process = withNativeLibraryPath(
            ProcessBuilder(args),
            ctx.nativeLibDir,
            File(args.first()).parentFile
        ).redirectErrorStream(true)
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

    // Acumulador de diagnóstico por candidato para aislar fallos en Android 15 / HyperOS
    private val lastCandidateTrace = ThreadLocal<MutableList<String>>().apply { set(mutableListOf()) }

    private fun recordCandidateReason(ctx: BuildContext?, candidate: File, reason: String) {
        val entry = "[${candidate.absolutePath}] -> $reason"
        val list = lastCandidateTrace.get() ?: mutableListOf<String>().also { lastCandidateTrace.set(it) }
        list.add(entry)
        ctx?.log(LogLevel.WARNING, "AAPT2", entry)
    }

    private fun consumeCandidateTrace(): String {
        val list = lastCandidateTrace.get() ?: return ""
        val joined = list.joinToString(" | ")
        list.clear()
        return joined
    }

    private fun ensureBundledSupportLibs(binDir: File, ctx: BuildContext) {
        val supportNames = listOf(
            "libz.so.1",
            "libexpat.so.1",
            "libfmt.so",
            "libpng16.so",
            "libprotobuf.so",
            "libabsl_log_internal_message.so",
            "libabsl_hash.so",
            "libc++_shared.so"
        )
        val appDataDir = resolveWritableAppDataDir(ctx)
        for (libName in supportNames) {
            try {
                val targetLib = File(binDir, libName)
                if (targetLib.exists() && targetLib.length() > 0L) continue

                val diskCandidates = listOfNotNull(
                    File(ctx.nativeLibDir, libName),
                    appDataDir?.let { File(it, "lib/arm64/$libName") },
                    File("/data/data/com.termux/files/usr/lib/$libName")
                )
                val diskSource = diskCandidates.firstOrNull { it.exists() && it.isFile }
                if (diskSource != null) {
                    diskSource.inputStream().use { input ->
                        targetLib.outputStream().use { output -> input.copyTo(output) }
                    }
                    targetLib.setReadable(true, false)
                    targetLib.setExecutable(true, false)
                    continue
                }

                val assetStream = javaClass.classLoader?.getResourceAsStream("assets/bin/$libName")
                if (assetStream != null) {
                    assetStream.use { input ->
                        targetLib.outputStream().use { output -> input.copyTo(output) }
                    }
                    targetLib.setReadable(true, false)
                    targetLib.setExecutable(true, false)
                }
            } catch (t: Throwable) {
                ctx.log(LogLevel.WARNING, "AAPT2", "No se pudo preparar $libName en ${binDir.absolutePath}: ${t.message}")
            }
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
