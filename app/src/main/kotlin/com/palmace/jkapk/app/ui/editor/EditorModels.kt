package com.palmace.jkapk.app.ui.editor

import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.Severity
import java.io.File

data class EditorTabState(
    val file: File,
    val relativePath: String,
    val title: String,
    val content: String,
    val isDirty: Boolean = false,
    val cursorIndex: Int = 0
)

data class DiagnosticMarker(
    val file: File,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
    val diagnostic: BuildDiagnostic
)

data class CursorTarget(
    val file: File,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val charOffset: Int,
    val message: String? = null
)

data class EditorUiState(
    val tabs: List<EditorTabState> = emptyList(),
    val activeTabPath: String? = null,
    val markersByPath: Map<String, List<DiagnosticMarker>> = emptyMap(),
    val pendingJump: CursorTarget? = null,
    val statusMessage: String? = null
) {
    val activeTab: EditorTabState?
        get() = tabs.firstOrNull { it.relativePath == activeTabPath }

    val activeMarkers: List<DiagnosticMarker>
        get() = activeTabPath?.let { markersByPath[it] }.orEmpty()
}
