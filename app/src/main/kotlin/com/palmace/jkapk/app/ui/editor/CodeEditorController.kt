package com.palmace.jkapk.app.ui.editor

import com.palmace.jkapk.app.domain.ProjectWorkspace
import com.palmace.jkapk.engine.BuildDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class CodeEditorController(
    private val navigator: DiagnosticNavigator = DiagnosticNavigator()
) {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private inline fun updateState(transform: (EditorUiState) -> EditorUiState) {
        synchronized(_uiState) {
            _uiState.value = transform(_uiState.value)
        }
    }

    fun openFile(workspace: ProjectWorkspace, file: File) {
        if (!file.exists() || !file.isFile) return
        val relPath = runCatching {
            file.relativeTo(workspace.rootDir).invariantSeparatorsPath
        }.getOrElse { file.name }

        val current = _uiState.value
        val existingTab = current.tabs.firstOrNull { it.relativePath == relPath }
        val updatedTabs = if (existingTab != null) {
            current.tabs
        } else {
            val text = runCatching { file.readText() }.getOrDefault("")
            current.tabs + EditorTabState(
                file = file,
                relativePath = relPath,
                title = file.name,
                content = text,
                isDirty = false,
                cursorIndex = 0
            )
        }

        updateState {
            it.copy(
                tabs = updatedTabs,
                activeTabPath = relPath,
                statusMessage = "Abierto: $relPath"
            )
        }
    }

    fun onTextChanged(newContent: String, cursorIndex: Int) {
        val current = _uiState.value
        val activePath = current.activeTabPath ?: return
        val updatedTabs = current.tabs.map { tab ->
            if (tab.relativePath == activePath) {
                val dirty = tab.isDirty || (tab.content != newContent)
                tab.copy(
                    content = newContent,
                    isDirty = dirty,
                    cursorIndex = cursorIndex.coerceAtLeast(0)
                )
            } else {
                tab
            }
        }
        updateState { it.copy(tabs = updatedTabs) }
    }

    fun saveActiveTab(workspace: ProjectWorkspace) {
        val current = _uiState.value
        val active = current.activeTab ?: return
        runCatching {
            active.file.parentFile?.mkdirs()
            active.file.writeText(active.content)
        }
        val updatedTabs = current.tabs.map { tab ->
            if (tab.relativePath == active.relativePath) tab.copy(isDirty = false) else tab
        }
        updateState {
            it.copy(
                tabs = updatedTabs,
                statusMessage = "Guardado: ${active.relativePath}"
            )
        }
    }

    fun applyDiagnostics(workspace: ProjectWorkspace, diagnostics: List<BuildDiagnostic>) {
        val markers = navigator.buildMarkersByFile(workspace, diagnostics)
        updateState { it.copy(markersByPath = markers) }
    }

    fun jumpToDiagnostic(workspace: ProjectWorkspace, diagnostic: BuildDiagnostic) {
        val activeTab = _uiState.value.activeTab
        val override = if (activeTab != null && diagnostic.source?.relativePath == activeTab.relativePath) {
            activeTab.content
        } else {
            null
        }
        val target = navigator.resolveCursorTarget(workspace, diagnostic, override) ?: return
        openFile(workspace, target.file)
        updateState { it.copy(pendingJump = target) }
    }

    fun consumePendingJump() {
        updateState { it.copy(pendingJump = null) }
    }
}
