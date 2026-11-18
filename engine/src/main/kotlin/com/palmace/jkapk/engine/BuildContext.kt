package com.palmace.jkapk.engine

import java.io.File

enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

interface BuildContext {
    val config: ProjectConfig
    val workDir: File
    val cacheDir: File
    val nativeLibDir: File
    val cancelled: Boolean

    fun emit(state: BuildState)
    fun log(level: LogLevel, tag: String, message: String)
    fun diagnostic(diagnostic: BuildDiagnostic)
    fun checkCancelled()
}
