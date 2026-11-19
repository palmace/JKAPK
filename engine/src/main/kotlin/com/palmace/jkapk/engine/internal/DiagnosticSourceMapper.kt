package com.palmace.jkapk.engine.internal

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.SourceRef
import java.io.File

// Normaliza ubicaciones de diagnostico hacia SourceRef de forma consistente
// a traves de AAPT2, Kotlin, Java y D8.
internal object DiagnosticSourceMapper {

    fun fromPath(
        ctx: BuildContext,
        rawPath: String?,
        line: Int? = null,
        column: Int? = null
    ): SourceRef? {
        if (rawPath.isNullOrBlank()) return null

        val targetFile = File(rawPath)
        val projectRootCanonical = runCatching { ctx.config.projectRoot.canonicalFile }
            .getOrDefault(ctx.config.projectRoot.absoluteFile)
        val targetCanonical = runCatching { targetFile.canonicalFile }
            .getOrDefault(targetFile.absoluteFile)

        val rootPath = projectRootCanonical.path.replace('\\', '/').trimEnd('/')
        val filePath = targetCanonical.path.replace('\\', '/')

        val (baseRoot, relPath) = if (filePath.startsWith("$rootPath/")) {
            ctx.config.projectRoot to filePath.removePrefix("$rootPath/")
        } else {
            val workRootCanonical = runCatching { ctx.workDir.canonicalFile }
                .getOrDefault(ctx.workDir.absoluteFile)
            val workPath = workRootCanonical.path.replace('\\', '/').trimEnd('/')
            if (filePath.startsWith("$workPath/")) {
                ctx.workDir to filePath.removePrefix("$workPath/")
            } else {
                ctx.config.projectRoot to rawPath.replace('\\', '/')
            }
        }

        return SourceRef(
            projectRoot = baseRoot,
            relativePath = relPath,
            line = line?.takeIf { it > 0 },
            column = column?.takeIf { it > 0 }
        )
    }
}
