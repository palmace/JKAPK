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
        val generatedRDirs = listOfNotNull(
            input.generatedJavaDir?.takeIf { it.exists() },
            File(ctx.workDir, "generated/source/r").takeIf { it.exists() }
        ).distinctBy { it.absolutePath }
        val rJavaFiles = collectFiles(generatedRDirs, "java")
        val generatedRKt = generateRKotlinSource(rJavaFiles, File(ctx.workDir, "generated/source/r_kt/GeneratedR.kt"), ctx)
        val javaFilesForSymbols = collectFiles(ctx.config.javaSourceDirs, "java")

        if (kotlinFiles.isEmpty()) {
            ctx.emit(BuildState.CompilingKotlin("(sin archivos)", 0, 0))
            ctx.log(LogLevel.INFO, "KotlinCompiler", "Sin fuentes Kotlin, saltando")
            return input
        }

        val outputDir = File(ctx.workDir, "classes/kotlin").apply { mkdirs() }

        // Buscar kotlin-stdlib en la cache de Gradle (necesario para compilar)
fun resolveValidJar(f: File?): File? = f?.takeIf { it.exists() && it.length() > 10_000L }

val gradleStdlib = File(System.getProperty("user.home") ?: "", ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib")
    .takeIf { it.exists() }
    ?.walkTopDown()
    ?.firstOrNull { it.name.startsWith("kotlin-stdlib-") && it.name.endsWith(".jar") && it.length() > 10_000L }

val candidateStdlibFiles = listOfNotNull(
    gradleStdlib,
    resolveValidJar(File(ctx.cacheDir, "kotlin-stdlib.jar")),
    resolveValidJar(File(ctx.config.appCacheDir, "kotlin-stdlib.jar")),
    resolveValidJar(File(ctx.config.appCacheDir, "kotlin-stdlib-1.9.24.jar")),
    resolveValidJar(File(ctx.config.appCacheDir.parentFile ?: ctx.config.appCacheDir, "cache/kotlin-stdlib.jar"))
)

var stdlibJar = candidateStdlibFiles.firstOrNull()
if (stdlibJar == null) {
    try {
        val appCls = Class.forName("com.palmace.jkapk.app.JkapkApplication")
        val appInst = try {
                    appCls.getMethod("getInstance").invoke(null)
                } catch (_: Throwable) {
                    val comp = appCls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                    comp.javaClass.getMethod("getInstance").invoke(comp)
                }
        val assetsObj = appCls.getMethod("getAssets").invoke(appInst)
        val openMethod = assetsObj.javaClass.getMethod("open", String::class.java)
        val assetNames = listOf("kotlin-stdlib.jar", "kotlin-stdlib-1.9.24.jar", "kotlin-stdlib-2.4.21.jar")
        for (assetName in assetNames) {
            try {
                val dst = File(ctx.cacheDir, "kotlin-stdlib.jar")
                (openMethod.invoke(assetsObj, assetName) as? java.io.InputStream)?.use { inp ->
                    dst.outputStream().buffered(65536).use { out -> inp.copyTo(out, bufferSize = 65536) }
                }
                if (resolveValidJar(dst) != null) {
                    stdlibJar = dst
                    ctx.log(LogLevel.INFO, "KotlinCompiler", "Extraido $assetName desde assets (${dst.length()} B)")
                    break
                }
            } catch (_: Throwable) {
            }
        }
    } catch (_: Throwable) {
    }
}
if (stdlibJar == null) {
    stdlibJar = listOfNotNull(
        ctx.cacheDir.takeIf { it.exists() }?.walkTopDown()?.firstOrNull { it.name.contains("kotlin-stdlib") && it.name.endsWith(".jar") && it.length() > 10_000L },
        ctx.config.appCacheDir.takeIf { it.exists() }?.walkTopDown()?.firstOrNull { it.name.contains("kotlin-stdlib") && it.name.endsWith(".jar") && it.length() > 10_000L }
    ).firstOrNull()
}
ctx.log(
    LogLevel.INFO,
    "KotlinCompiler",
    "stdlibJar=${stdlibJar?.absolutePath ?: "NONE"} (${stdlibJar?.length() ?: 0L}B), inputJars=${input.jars.size}, androidJar=${ctx.config.androidJar.absolutePath} (${ctx.config.androidJar.length()}B)"
)

val baseJars = if (stdlibJar != null && stdlibJar !in input.jars) {
    input.jars + stdlibJar
} else {
    input.jars
}
val androidJarFile = ctx.config.androidJar
val allJars = if (androidJarFile.exists() && androidJarFile.length() > 0L && androidJarFile !in baseJars) {
    baseJars + androidJarFile
} else {
    baseJars
}

ctx.log(LogLevel.INFO, "KotlinCompiler", "Classpath: " + allJars.joinToString(":") { it.name })
        val classpath = allJars.joinToString(File.pathSeparator) { it.absolutePath }
        val allSources = kotlinFiles + javaFilesForSymbols + listOfNotNull(generatedRKt)

        val kotlinHomeDir = File(ctx.workDir, "kotlin-home").apply {
            File(this, "lib").mkdirs()
        }
        val homeLibDir = File(kotlinHomeDir, "lib")
        val candidateStdlibs = listOfNotNull(
            stdlibJar,
            File(ctx.config.appCacheDir, "kotlin-stdlib.jar").takeIf { it.exists() },
            File(ctx.config.appCacheDir, "kotlin-stdlib-1.9.24.jar").takeIf { it.exists() }
        )
        candidateStdlibs.firstOrNull()?.let { srcLib ->
            val dstLib = File(homeLibDir, "kotlin-stdlib.jar")
            if (!dstLib.exists() || dstLib.length() != srcLib.length()) {
                srcLib.copyTo(dstLib, overwrite = true)
            }
        }
        System.setProperty("kotlin.home", kotlinHomeDir.absolutePath)

        val args = arrayOf(
            "-kotlin-home", kotlinHomeDir.absolutePath,
            "-d", outputDir.absolutePath,
            "-classpath", classpath,
            "-no-stdlib",
            "-no-reflect",
            "-no-jdk",
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

        // Cargar el compilador desde el JAR extraido en appCacheDir (con diagnostico y respaldo)
        val compilerJar = File(ctx.config.appCacheDir, "kotlin-compiler-embeddable.jar")
        if (!compilerJar.exists() || compilerJar.length() <= 0L) {
            val cacheListing = ctx.config.appCacheDir.listFiles()
                ?.joinToString(", ") { "${it.name}(${it.length()}B)" }
                ?: "<dir no existe o no legible>"
            ctx.log(
                LogLevel.entries.firstOrNull { it.name.contains("WARN", ignoreCase = true) } ?: LogLevel.entries.first(),
                "KotlinCompiler",
                "Falta ${compilerJar.absolutePath} (exists=${compilerJar.exists()}, size=${compilerJar.length()}). En appCacheDir: $cacheListing"
            )
            val fallbackCandidates = listOfNotNull(
                File(ctx.config.appCacheDir.parentFile ?: ctx.config.appCacheDir, "cache/kotlin-compiler-embeddable.jar"),
                File(System.getProperty("user.home") ?: "", ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.24")
                    .takeIf { it.exists() }
                    ?.walkTopDown()
                    ?.firstOrNull { it.name.endsWith(".jar") && it.length() > 10_000_000L }
            )
            val foundFallback = fallbackCandidates.firstOrNull { it.exists() && it.length() > 0L }
            if (foundFallback != null && foundFallback.absolutePath != compilerJar.absolutePath) {
                compilerJar.parentFile?.mkdirs()
                foundFallback.copyTo(compilerJar, overwrite = true)
                ctx.log(LogLevel.INFO, "KotlinCompiler", "Copiado JAR desde respaldo: ${foundFallback.absolutePath}")
            }
        }
        if (!compilerJar.exists() || compilerJar.length() <= 0L) {
            try {
                if (compilerJar.exists() && compilerJar.length() == 0L) compilerJar.delete()
                val appCls = Class.forName("com.palmace.jkapk.app.JkapkApplication")
                val appInst = try {
                    appCls.getMethod("getInstance").invoke(null)
                } catch (_: Throwable) {
                    val compField = appCls.getDeclaredField("Companion").apply { isAccessible = true }
                    val compObj = compField.get(null)
                    compObj.javaClass.getMethod("getInstance").invoke(compObj)
                }
                val assetsObj = appCls.getMethod("getAssets").invoke(appInst)
                val openMethod = assetsObj.javaClass.getMethod("open", String::class.java)
                val tmpFile = File(compilerJar.parentFile, "kotlin-compiler-embeddable.jar.tmp")
                if (tmpFile.exists()) tmpFile.delete()
                (openMethod.invoke(assetsObj, "kotlin-compiler-embeddable.jar") as java.io.InputStream).use { assetStream ->
                    tmpFile.outputStream().buffered(65536).use { output ->
                        assetStream.copyTo(output, bufferSize = 65536)
                        output.flush()
                    }
                }
                if (tmpFile.length() > 10_000_000L) {
                    if (compilerJar.exists()) compilerJar.delete()
                    if (!tmpFile.renameTo(compilerJar)) {
                        tmpFile.copyTo(compilerJar, overwrite = true)
                        tmpFile.delete()
                    }
                    ctx.log(LogLevel.INFO, "KotlinCompiler", "Extraido desde assets: ${compilerJar.length()} bytes")
                } else {
                    if (tmpFile.exists()) tmpFile.delete()
                }
            } catch (ex: Throwable) {
                ctx.log(
                    LogLevel.entries.firstOrNull { it.name.contains("WARN", ignoreCase = true) } ?: LogLevel.entries.first(),
                    "KotlinCompiler",
                    "Fallo respaldo desde assets: ${ex.message}"
                )
            }
        }
        if (!compilerJar.exists() || compilerJar.length() <= 0L) {
            throw KotlinCompilationException(
                "No se encontro kotlin-compiler-embeddable.jar en ${compilerJar.absolutePath} (size=${compilerJar.length()})",
                emptyList()
            )
        }

        System.setProperty("jkapk.compiler.jar", compilerJar.absolutePath)
        val prevContextLoader = Thread.currentThread().contextClassLoader
        val exitCode = try {
            val loaderUrls = buildList {
                stdlibJar?.takeIf { it.exists() && it.length() > 0L }?.let { add(it.toURI().toURL()) }
                add(compilerJar.toURI().toURL())
            }.toTypedArray()
            val classLoader = java.net.URLClassLoader(
                loaderUrls,
                this::class.java.classLoader
            )
            Thread.currentThread().contextClassLoader = classLoader
            val compilerClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            val compiler = compilerClass.getDeclaredConstructor().newInstance()
            val execMethod = compilerClass.getMethod(
                "exec",
                java.io.PrintStream::class.java,
                Array<String>::class.java
            )
            execMethod.invoke(compiler, errStream, args) as org.jetbrains.kotlin.cli.common.ExitCode
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
            Thread.currentThread().contextClassLoader = prevContextLoader
            errStream.flush()
        }

        val rawOutput = buffer.toString("UTF-8")
        val diagnostics = KotlinOutputParser.parse(rawOutput, ctx)
        diagnostics.forEach { ctx.diagnostic(it) }

        val errorCount = diagnostics.count { it.severity == Severity.ERROR }

        if (exitCode.code != 0 || errorCount > 0) {
            throw KotlinCompilationException(
                "Kotlin fallo con $errorCount error(es). Salida: $rawOutput",
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


// Genera un JAR con clases .class para R y sus clases internas (R$id, R$layout, R$string, etc.)
// evitando que K2JVMCompiler con -no-jdk falle con 'unresolved supertypes: Object' en R.java.
private fun generateRKotlinSource(rJavaFiles: List<File>, outKt: File, ctx: BuildContext): File? {
    if (rJavaFiles.isEmpty()) {
        ctx.log(LogLevel.INFO, "KotlinCompiler", "rJavaFiles vacio, omitiendo GeneratedR.kt")
        return null
    }
    return try {
        val pkgRegex = Regex("""^\s*package\s+([a-zA-Z0-9_.]+)\s*;""", RegexOption.MULTILINE)
        val innerStartRegex = Regex("""public\s+static\s+(?:final\s+)?class\s+([a-zA-Z0-9_]+)\s*\{""")
        val intConstRegex = Regex("""public\s+static\s+(?:final\s+)?int\s+([a-zA-Z0-9_]+)\s*=\s*(-?0x[0-9a-fA-F]+|-?\d+)\s*;""")

        val sb = StringBuilder()
        var addedAny = false

        for (rFile in rJavaFiles) {
            val content = rFile.readText(charset = Charsets.UTF_8)
            val pkg = pkgRegex.find(content)?.groupValues?.get(1) ?: ctx.config.packageName
            val innerMatches = innerStartRegex.findAll(content).toList()
            if (innerMatches.isEmpty()) continue

            sb.append("package ").append(pkg).append("\n\n")
            sb.append("object R {\n")

            for ((i, match) in innerMatches.withIndex()) {
                val innerName = match.groupValues[1]
                val blockStart = match.range.last + 1
                val blockEnd = if (i + 1 < innerMatches.size) innerMatches[i + 1].range.first else content.length
                val block = content.substring(blockStart, blockEnd)

                sb.append("    object ").append(innerName).append(" {\n")
                for (fm in intConstRegex.findAll(block)) {
                    val fName = fm.groupValues[1]
                    val rawVal = fm.groupValues[2]
                    val parsedVal = try {
                        if (rawVal.startsWith("0x", ignoreCase = true)) {
                            java.lang.Long.decode(rawVal).toInt()
                        } else if (rawVal.startsWith("-0x", ignoreCase = true)) {
                            -java.lang.Long.decode(rawVal.replaceFirst("-", "")).toInt()
                        } else {
                            rawVal.toInt()
                        }
                    } catch (_: Throwable) {
                        0
                    }
                    sb.append("        const val ").append(fName).append(": Int = ").append(parsedVal).append("\n")
                }
                sb.append("    }\n")
            }

            sb.append("}\n\n")
            addedAny = true
        }

        if (!addedAny) {
            ctx.log(LogLevel.WARNING, "KotlinCompiler", "No se encontraron clases internas en R.java")
            return null
        }

        outKt.parentFile?.mkdirs()
        outKt.writeText(sb.toString(), charset = Charsets.UTF_8)
        ctx.log(LogLevel.INFO, "KotlinCompiler", "Generado GeneratedR.kt (${outKt.length()}B)")
        outKt
    } catch (t: Throwable) {
        ctx.log(LogLevel.WARNING, "KotlinCompiler", "Error generando GeneratedR.kt: ${t.message}")
        null
    }
}
