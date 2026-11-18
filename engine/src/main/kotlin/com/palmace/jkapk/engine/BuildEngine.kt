package com.palmace.jkapk.engine

import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface BuildEngine {
    val state: StateFlow<BuildState>

    suspend fun build(config: ProjectConfig): BuildResult

    fun cancel()

    suspend fun clean(config: ProjectConfig)
}

data class BuildResult(
    val success: Boolean,
    val apk: File?,
    val diagnostics: List<BuildDiagnostic>,
    val durationMs: Long
)
