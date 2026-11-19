package com.palmace.jkapk.engine.internal.resolve

import java.io.File

// Resultado de la resolucion, enriquecido a lo largo del pipeline.
data class ResolvedClasspath(
    val jars: List<File>,
    val aarResources: List<File> = emptyList(),
    val aarManifests: List<File> = emptyList(),
    val generatedJavaDir: File? = null,
    val compiledResourcesApk: File? = null,
    val dexFiles: List<File> = emptyList(),
    val unsignedApk: File? = null,
    val signedApk: File? = null
) {
    val isEmpty: Boolean get() = jars.isEmpty()
}
