package com.palmace.jkapk.engine.internal

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultBuildContext(
    override val config: ProjectConfig,
    override val workDir: File,
    override val cacheDir: File,
    override val nativeLibDir: File,
    private val stateFlow: MutableStateFlow<BuildState>,
    private val logger: (LogLevel, String, String) -> Unit,
    private val diagnosticSink: (BuildDiagnostic) -> Unit
) : BuildContext {

    private val cancelFlag = AtomicBoolean(false)

    override val cancelled: Boolean
        get() = cancelFlag.get()

    fun requestCancel() {
        cancelFlag.set(true)
    }

    override fun emit(state: BuildState) {
        stateFlow.value = state
    }

    override fun log(level: LogLevel, tag: String, message: String) {
        logger(level, tag, message)
    }

    override fun diagnostic(diagnostic: BuildDiagnostic) {
        diagnosticSink(diagnostic)
    }

    override fun checkCancelled() {
        if (cancelFlag.get()) {
            throw CancellationException("Build cancelado por el usuario")
        }
    }
}
