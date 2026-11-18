package com.palmace.jkapk.engine

import java.io.File

enum class Severity { INFO, WARNING, ERROR }

data class SourceRef(
    val projectRoot: File,
    val relativePath: String,
    val line: Int? = null,
    val column: Int? = null
) {
    fun toFile(): File = File(projectRoot, relativePath)
}

data class BuildDiagnostic(
    val severity: Severity,
    val message: String,
    val source: SourceRef? = null,
    val stage: BuildStage,
    val rawOutput: String? = null
)
