package com.palmace.jkapk.engine.internal

import com.palmace.jkapk.engine.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DefaultBuildEngine : BuildEngine {

    private val _state = MutableStateFlow<BuildState>(BuildState.Idle)
    override val state: StateFlow<BuildState> = _state.asStateFlow()

    private var cancelled = false

    override suspend fun build(config: ProjectConfig): BuildResult {
        cancelled = false
        val start = System.currentTimeMillis()
        _state.value = BuildState.Failed(
            stage = BuildStage.RESOLVE,
            diagnostics = listOf(
                BuildDiagnostic(
                    severity = Severity.ERROR,
                    message = "DefaultBuildEngine aún no implementado (Fase 2).",
                    stage = BuildStage.RESOLVE
                )
            )
        )
        return BuildResult(
            success = false,
            apk = null,
            diagnostics = (state.value as BuildState.Failed).diagnostics,
            durationMs = System.currentTimeMillis() - start
        )
    }

    override fun cancel() {
        cancelled = true
        _state.value = BuildState.Cancelled
    }

    override suspend fun clean(config: ProjectConfig) {
        // TODO Fase 2: borrar cacheDir del proyecto.
    }
}
