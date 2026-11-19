package com.palmace.jkapk.app.data

import com.palmace.jkapk.app.domain.ProjectWorkspace
import java.io.File

data class ProjectFileEntry(
    val relativePath: String,
    val file: File,
    val extension: String,
    val sizeBytes: Long
)

class ProjectScanner {

    fun discoverProjects(projectsRoot: File): List<ProjectWorkspace> {
        if (!projectsRoot.exists() || !projectsRoot.isDirectory) return emptyList()
        return projectsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { ProjectWorkspace.loadFromDir(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun listSourceAndResFiles(workspace: ProjectWorkspace): List<ProjectFileEntry> {
        val candidates = listOfNotNull(
            workspace.manifestFile.takeIf { it.exists() },
            workspace.configFile.takeIf { it.exists() },
            workspace.kotlinDir.takeIf { it.exists() },
            workspace.javaDir.takeIf { it.exists() },
            workspace.resDir.takeIf { it.exists() },
            workspace.assetsDir.takeIf { it.exists() },
            workspace.libsDir.takeIf { it.exists() }
        )

        val collected = mutableListOf<ProjectFileEntry>()
        for (target in candidates) {
            if (target.isFile) {
                collected += toEntry(workspace.rootDir, target)
            } else {
                target.walkTopDown()
                    .filter { it.isFile }
                    .forEach { collected += toEntry(workspace.rootDir, it) }
            }
        }

        return collected.sortedBy { it.relativePath.lowercase() }
    }

    private fun toEntry(rootDir: File, file: File): ProjectFileEntry {
        val rel = file.relativeTo(rootDir).invariantSeparatorsPath
        return ProjectFileEntry(
            relativePath = rel,
            file = file,
            extension = file.extension.lowercase(),
            sizeBytes = file.length()
        )
    }
}
