package com.palmace.jkapk.engine.internal.resolve

import java.io.File

/**
 * Recolecta .jar y .aar de los directorios libs/.
 * No extrae .aar (eso lo hace AarExtractor); solo los clasifica.
 */
internal class LocalJarCollector {

    fun collect(libsDirs: List<File>): LocalArtifacts {
        val jars = mutableListOf<File>()
        val aars = mutableListOf<File>()

        libsDirs.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    when (file.extension.lowercase()) {
                        "jar" -> jars.add(file)
                        "aar" -> aars.add(file)
                    }
                }
        }

        return LocalArtifacts(jars = jars.distinct(), aars = aars.distinct())
    }
}

data class LocalArtifacts(
    val jars: List<File>,
    val aars: List<File>
)
