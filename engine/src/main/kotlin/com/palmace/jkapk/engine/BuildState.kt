package com.palmace.jkapk.engine

import java.io.File

sealed interface BuildState {
    data object Idle : BuildState

    data class ResolvingDeps(
        val message: String,
        val done: Int = 0,
        val total: Int = 0
    ) : BuildState

    data class CompilingJava(
        val currentFile: String,
        val done: Int,
        val total: Int
    ) : BuildState

    data class CompilingKotlin(
        val currentFile: String,
        val done: Int,
        val total: Int
    ) : BuildState

    data class ProcessingResources(val details: String) : BuildState

    data class Dexing(val batchIndex: Int, val totalBatches: Int) : BuildState

    data class Packaging(val phase: String) : BuildState

    data class Signing(val apkPath: String) : BuildState

    data class Success(
        val apk: File,
        val durationMs: Long,
        val diagnostics: List<BuildDiagnostic>
    ) : BuildState

    data class Failed(
        val stage: BuildStage,
        val diagnostics: List<BuildDiagnostic>,
        val cause: Throwable? = null
    ) : BuildState

    data object Cancelled : BuildState
}
