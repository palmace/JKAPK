package com.palmace.jkapk.engine.internal.resolve

import java.io.File

// Resultado de la resolucion de dependencias locales.
// jars: classpath para javac y kotlinc.
// aarResources: directorios res/ de cada .aar (para Fase 5).
// aarManifests: AndroidManifest.xml de cada .aar (para Fase 7).
data class ResolvedClasspath(
    val jars: List<File>,
    val aarResources: List<File> = emptyList(),
    val aarManifests: List<File> = emptyList()
) {
    val isEmpty: Boolean get() = jars.isEmpty()
}
