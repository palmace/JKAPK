package com.palmace.jkapk.engine.internal

import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildEngine
import com.palmace.jkapk.engine.BuildResult
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.Severity
import com.palmace.jkapk.engine.internal.stage.Aapt2Stage
import com.palmace.jkapk.engine.internal.stage.DependencyResolverStage
import com.palmace.jkapk.engine.internal.stage.DexStage
import com.palmace.jkapk.engine.internal.stage.JavaCompilerStage
import com.palmace.jkapk.engine.internal.stage.KotlinCompilerStage
import com.palmace.jkapk.engine.internal.stage.PackageStage
import com.palmace.jkapk.engine.internal.stage.SignStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

internal class DefaultBuildEngine(
    private val logSink: ((level: String, tag: String, message: String) -> Unit)? = null
) : BuildEngine {
    constructor() : this(null)

    private val _state = MutableStateFlow<BuildState>(BuildState.Idle)
    override val state: StateFlow<BuildState> = _state.asStateFlow()

    private var activeContext: DefaultBuildContext? = null

    override suspend fun build(config: ProjectConfig): BuildResult {
        val start = System.currentTimeMillis()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val projectHash = hashOf(config.projectRoot.absolutePath)
        val workDir = File(config.buildOutputDir, "work-$projectHash-${System.currentTimeMillis()}")
        val cacheDir = File(config.buildOutputDir, "cache-$projectHash")
        val nativeLibDir = config.aapt2Binary?.parentFile?.takeIf { it.isDirectory }
            ?: config.internalBinDirs.firstOrNull { it.isDirectory }
            ?: config.internalBinDirs.firstOrNull()
            ?: File(config.buildOutputDir, "native-bin")

        workDir.mkdirs()
        cacheDir.mkdirs()

        val ctx = DefaultBuildContext(
            config = config,
            workDir = workDir,
            cacheDir = cacheDir,
            nativeLibDir = nativeLibDir,
            stateFlow = _state,
            logger = { level, tag, msg -> logSink?.invoke(level.name, tag, msg) },
            diagnosticSink = { diagnostics.add(it) }
        )
        activeContext = ctx

        // Orden: Resolve -> AAPT2 -> Kotlin -> Java -> DEX -> Package -> Sign
        val stages = listOf(
            DependencyResolverStage(),
            Aapt2Stage(),
            KotlinCompilerStage(),
            JavaCompilerStage(),
            DexStage(),
            PackageStage(),
            SignStage()
        )
        val pipeline = Pipeline(stages)

        return try {
            pipeline.execute(config, ctx)

            val apkPath = File(workDir, "${config.projectName}.apk")
            val duration = System.currentTimeMillis() - start
            _state.value = BuildState.Success(apkPath, duration, diagnostics.toList())
            BuildResult(
                success = true,
                apk = apkPath,
                diagnostics = diagnostics.toList(),
                durationMs = duration
            )
        } catch (ce: CancellationException) {
            _state.value = BuildState.Cancelled
            BuildResult(
                success = false,
                apk = null,
                diagnostics = diagnostics.toList(),
                durationMs = System.currentTimeMillis() - start
            )
        } catch (t: Throwable) {
            val failedStage = when (t) {
                is com.palmace.jkapk.engine.internal.stage.Aapt2UnavailableException -> BuildStage.RESOURCES
                is com.palmace.jkapk.engine.internal.stage.Aapt2ExecutionException -> BuildStage.RESOURCES
                else -> BuildStage.RESOLVE
            }
            val failureDiag = BuildDiagnostic(
                severity = Severity.ERROR,
                message = "Fallo en ${failedStage.name}: ${t.message}",
                stage = failedStage,
                rawOutput = t.stackTraceToString()
            )
            diagnostics.add(failureDiag)
            _state.value = BuildState.Failed(failedStage, diagnostics.toList(), t)
            BuildResult(
                success = false,
                apk = null,
                diagnostics = diagnostics.toList(),
                durationMs = System.currentTimeMillis() - start
            )
        } finally {
            activeContext = null
        }
    }

    override fun cancel() {
        activeContext?.requestCancel()
        _state.value = BuildState.Cancelled
    }

    override suspend fun clean(config: ProjectConfig) {
        val projectHash = hashOf(config.projectRoot.absolutePath)
        val cacheDir = File(config.buildOutputDir, "cache-$projectHash")
        cacheDir.deleteRecursively()
        _state.value = BuildState.Idle
    }

    private fun hashOf(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
