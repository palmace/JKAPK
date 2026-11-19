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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Ensambla el APK sin firmar a partir de:
//   - resources.ap_ (de Aapt2Stage): resources.arsc + res/* + AndroidManifest.xml binario
//   - dexFiles (de DexStage): classes.dex, classes2.dex, ...
//   - assetDir (de ProjectConfig): assets/
//
// Requisitos Android 11+:
//   - resources.arsc debe ir STORED (sin comprimir)
//   - AndroidManifest.xml binario (viene de resources.ap_)
//   - .so (si los hubiera) STORED (apksig alinea a 16384 en Fase 7b)
//
// Invariante: resources.ap_ SIEMPRE contiene AndroidManifest.xml.
// Si no lo contiene, PackageExecutionException.
internal class PackageStage : BuildStageExecutor<ResolvedClasspath, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.PACKAGE

    override suspend fun execute(input: ResolvedClasspath, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()

        val resourcesAp = input.compiledResourcesApk
            ?: throw PackageExecutionException(
                "compiledResourcesApk es null. Aapt2Stage debe ejecutarse antes.",
                BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = "PackageStage sin resources.ap_ (Aapt2Stage no ejecutado o fallido)",
                    stage = BuildStage.PACKAGE
                )
            )

        if (!resourcesAp.exists()) {
            throw PackageExecutionException(
                "resources.ap_ no existe en ${resourcesAp.absolutePath}",
                BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = "resources.ap_ no encontrado",
                    stage = BuildStage.PACKAGE
                )
            )
        }

        if (input.dexFiles.isEmpty()) {
            throw PackageExecutionException(
                "dexFiles vacio. DexStage debe ejecutarse antes.",
                BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = "PackageStage sin .dex",
                    stage = BuildStage.PACKAGE
                )
            )
        }

        ctx.emit(BuildState.Packaging("Empaquetando APK"))
        ctx.log(
            LogLevel.INFO, "Package",
            "APK: ${input.dexFiles.size} dex + resources.ap_ (${resourcesAp.length()} bytes)"
        )

        val unsignedApk = File(ctx.workDir, "unsigned.apk")

        try {
            ZipOutputStream(unsignedApk.outputStream().buffered()).use { apk ->
                // 1. Descomprimir resources.ap_ y añadir entradas al APK.
                //    AndroidManifest.xml viene de aqui (binario, compilado por AAPT2).
                //    resources.arsc va STORED (Android 11+).
                var manifestFound = false
                ZipFile(resourcesAp).use { resourcesZip ->
                    resourcesZip.entries().asSequence()
                        .filter { !it.isDirectory }
                        .forEach { entry ->
                            ctx.checkCancelled()
                            val bytes = resourcesZip.getInputStream(entry).use { it.readBytes() }
                            when {
                                entry.name == "AndroidManifest.xml" -> {
                                    manifestFound = true
                                    // Manifest siempre DEFLATED (comprimido esta bien)
                                    apk.putNextEntry(ZipEntry(entry.name))
                                    apk.write(bytes)
                                    apk.closeEntry()
                                }
                                entry.name == "resources.arsc" -> {
                                    // Android 11+: STORED (sin comprimir)
                                    ZipAlignment.writeStored(apk, entry.name, bytes)
                                }
                                entry.name.endsWith(".dex") && entry.name.startsWith("classes") -> {
                                    // Dex de resources.ap_ (AAPT2 a veces incluye uno vacio).
                                    // Lo ignoramos; los dex reales vienen de input.dexFiles.
                                }
                                else -> {
                                    // res/*, etc. DEFLATED esta bien
                                    apk.putNextEntry(ZipEntry(entry.name))
                                    apk.write(bytes)
                                    apk.closeEntry()
                                }
                            }
                        }
                }

                if (!manifestFound) {
                    throw PackageExecutionException(
                        "resources.ap_ no contiene AndroidManifest.xml. " +
                            "APK invalido para Android (el manifest debe ser binario).",
                        BuildDiagnostic(
                            severity = Severity.ERROR,
                            message = "AndroidManifest.xml ausente en resources.ap_",
                            stage = BuildStage.PACKAGE
                        )
                    )
                }

                // 2. Añadir dex files como classes.dex, classes2.dex, ...
                input.dexFiles.forEachIndexed { index, dex ->
                    ctx.checkCancelled()
                    val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    apk.putNextEntry(ZipEntry(name))
                    dex.inputStream().use { it.copyTo(apk) }
                    apk.closeEntry()
                }

                // 3. Añadir assets/ si existe
                val assetsDir = ctx.config.assetDir
                if (assetsDir != null && assetsDir.isDirectory) {
                    assetsDir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            ctx.checkCancelled()
                            val rel = file.relativeTo(assetsDir).path.replace('\\', '/')
                            apk.putNextEntry(ZipEntry("assets/$rel"))
                            file.inputStream().use { it.copyTo(apk) }
                            apk.closeEntry()
                        }
                }

                // 4. jniLibs (lib/<abi>/*.so) si existieran en nativeLibDir.
                //    STORED (Android 15+ requiere alineacion a 16384, apksig la hace).
                val nativeLibDir = ctx.nativeLibDir
                if (nativeLibDir.isDirectory) {
                    nativeLibDir.walkTopDown()
                        .filter { it.isFile && it.extension == "so" }
                        .forEach { so ->
                            ctx.checkCancelled()
                            val rel = so.relativeTo(nativeLibDir).path.replace('\\', '/')
                            val bytes = so.readBytes()
                            ZipAlignment.writeStored(apk, "lib/$rel", bytes)
                        }
                }

                apk.finish()
            }
        } catch (t: PackageExecutionException) {
            throw t
        } catch (t: Throwable) {
            val diag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "Fallo al empaquetar APK: ${t.message}",
                stage = BuildStage.PACKAGE,
                rawOutput = t.stackTraceToString()
            )
            ctx.diagnostic(diag)
            throw PackageExecutionException("Fallo al empaquetar", diag)
        }

        ctx.log(LogLevel.INFO, "Package", "APK sin firmar: ${unsignedApk.absolutePath} (${unsignedApk.length()} bytes)")
        ctx.emit(BuildState.Packaging("(completado)"))

        return input.copy(unsignedApk = unsignedApk)
    }
}

internal class PackageExecutionException(
    message: String,
    val diagnostic: BuildDiagnostic
) : Exception(message)
