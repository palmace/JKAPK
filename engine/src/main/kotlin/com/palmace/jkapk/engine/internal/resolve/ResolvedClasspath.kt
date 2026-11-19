package com.palmace.jkapk.engine.internal.resolve

import java.io.File

// Resultado de la resolucion, enriquecido a lo largo del pipeline.
//   Resolve: jars, aarResources, aarManifests
//   AAPT2:   +generatedJavaDir, +compiledResourcesApk
//   Kotlin:  +classes/kotlin/ en jars
//   Java:    +classes/java/ en jars
//   DEX:     +dexFiles
data class ResolvedClasspath(
    val jars: List<File>,
    val aarResources: List<File> = emptyList(),
    val aarManifests: List<File> = emptyList(),
    val generatedJavaDir: File? = null,
    val compiledResourcesApk: File? = null,
    val dexFiles: List<File> = emptyList()
) {
    val isEmpty: Boolean get() = jars.isEmpty()
}
