package com.palmace.jkapk.engine

interface BuildEventListener {
    fun onStateChanged(state: BuildState) {}
    fun onLog(level: LogLevel, tag: String, message: String) {}
    fun onDiagnostic(diagnostic: BuildDiagnostic) {}
}
