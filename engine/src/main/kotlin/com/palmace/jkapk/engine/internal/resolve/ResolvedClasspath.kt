package com.palmace.jkapk.engine.internal.resolve

import java.io.File

// Resultado de la resolucion de dependencias locales.
// Se enriquece a lo largo del pipeline:
//   Resolve: jars, aarResources, aarManifests
//   AAPT2: +generatedJavaDir (R.java), +compiledResourcesApk (resources.ap_)
//   Kotlin: +classes/kotlin/ en jars
//   Java: +classes/java/ en jars
data class ResolvedClasspath(
    val jars: List<File>,
    val aarResources: List<File> = emptyList(),
    val aarManifests: List<File> = emptyList(),
    val generatedJavaDir: File? = null,
    val compiledResourcesApk: File? = null
) {
    val isEmpty: Boolean get() = jars.isEmpty()
}
