package com.palmace.jkapk.app.ui.editor

import com.palmace.jkapk.app.domain.ProjectWorkspace
import com.palmace.jkapk.engine.BuildDiagnostic
import java.io.File

class DiagnosticNavigator {

    fun buildMarkersByFile(
        workspace: ProjectWorkspace,
        diagnostics: List<BuildDiagnostic>
    ): Map<String, List<DiagnosticMarker>> {
        val markers = diagnostics.mapNotNull { diag ->
            val ref = diag.source ?: return@mapNotNull null
            val targetFile = resolveTargetFile(workspace, ref.relativePath) ?: return@mapNotNull null
            val relPath = normalizeRelativePath(workspace.rootDir, targetFile)
            DiagnosticMarker(
                file = targetFile,
                relativePath = relPath,
                line = (ref.line ?: 1).coerceAtLeast(1),
                column = (ref.column ?: 1).coerceAtLeast(1),
                severity = diag.severity,
                message = diag.message,
                diagnostic = diag
            )
        }

        return markers
            .groupBy { it.relativePath }
            .mapValues { (_, list) -> list.sortedWith(compareBy({ it.line }, { it.column })) }
    }

    fun resolveCursorTarget(
        workspace: ProjectWorkspace,
        diagnostic: BuildDiagnostic,
        contentOverride: String? = null
    ): CursorTarget? {
        val ref = diagnostic.source ?: return null
        val file = resolveTargetFile(workspace, ref.relativePath) ?: return null
        val relPath = normalizeRelativePath(workspace.rootDir, file)

        val line = (ref.line ?: 1).coerceAtLeast(1)
        val column = (ref.column ?: 1).coerceAtLeast(1)

        val text = contentOverride ?: file.takeIf { it.exists() && it.isFile }?.readText().orEmpty()
        val offset = computeCharOffset(text = text, targetLine = line, targetColumn = column)

        return CursorTarget(
            file = file,
            relativePath = relPath,
            line = line,
            column = column,
            charOffset = offset,
            message = diagnostic.message
        )
    }

    fun computeCharOffset(text: String, targetLine: Int, targetColumn: Int): Int {
        if (text.isEmpty()) return 0

        var currentLine = 1
        var lineStartIndex = 0
        var index = 0

        while (index < text.length && currentLine < targetLine) {
            if (text[index] == '\n') {
                currentLine++
                lineStartIndex = index + 1
            }
            index++
        }

        if (currentLine < targetLine) {
            return text.length
        }

        var lineEndIndex = text.indexOf('\n', startIndex = lineStartIndex)
        if (lineEndIndex == -1) {
            lineEndIndex = text.length
        }
        if (lineEndIndex > lineStartIndex && text[lineEndIndex - 1] == '\r') {
            lineEndIndex--
        }

        val lineLength = (lineEndIndex - lineStartIndex).coerceAtLeast(0)
        val columnOffset = (targetColumn - 1).coerceIn(0, lineLength)
        return (lineStartIndex + columnOffset).coerceIn(0, text.length)
    }

    fun resolveTargetFile(workspace: ProjectWorkspace, rawRelativePath: String): File? {
        val cleaned = rawRelativePath
            .trim()
            .removePrefix("./")
            .replace('\\', '/')

        if (cleaned.isEmpty()) return null

        val direct = File(workspace.rootDir, cleaned)
        if (direct.exists() && direct.isFile) return direct

        val strippedProjectPrefix = cleaned.removePrefix("${workspace.name}/")
        val fromProject = File(workspace.rootDir, strippedProjectPrefix)
        if (fromProject.exists() && fromProject.isFile) return fromProject

        val targetName = cleaned.substringAfterLast('/')
        val candidates = workspace.rootDir.walkTopDown()
            .filter { it.isFile }
            .toList()

        return candidates.firstOrNull { file ->
            val norm = file.relativeTo(workspace.rootDir).invariantSeparatorsPath
            norm == cleaned || norm.endsWith("/$cleaned")
        } ?: candidates.firstOrNull { it.name == targetName }
    }

    private fun normalizeRelativePath(rootDir: File, file: File): String {
        return runCatching {
            file.relativeTo(rootDir).invariantSeparatorsPath
        }.getOrElse { file.name }
    }
}
