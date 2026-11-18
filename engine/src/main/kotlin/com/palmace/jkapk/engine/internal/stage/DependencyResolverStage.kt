package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.internal.resolve.AarExtractor
import com.palmace.jkapk.engine.internal.resolve.LocalJarCollector
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import java.io.File

/**
 * Fase 3: resolución local de dependencias.
 * - Escanea libsDirs en busca de .jar y .aar.
 * - Extrae los .aar a cacheDir/aar/<hash>/.
 * - Construye ResolvedClasspath con todos los jars y recursos.
 *
 * En Fase 3.5 se añadirá resolución remota (Maven Central, Google Maven).
 * Los campos Dependency de ProjectConfig se ignoran aquí por ahora;
 * cuando llegue Fase 3.5, se consultarán y descargarán si preferLocal=false.
 */
internal class DependencyResolverStage(
    private val jarCollector: LocalJarCollector = LocalJarCollector(),
    private val aarExtractor: AarExtractor = AarExtractor()
) : BuildStageExecutor<ProjectConfig, ResolvedClasspath> {

    override val stage: BuildStage = BuildStage.RESOLVE

    override suspend fun execute(input: ProjectConfig, ctx: BuildContext): ResolvedClasspath {
        ctx.checkCancelled()
        ctx.emit(BuildState.ResolvingDeps("Escaneando libs/", 0, 0))
        ctx.log(LogLevel.INFO, "Resolve", "Escaneando ${input.libsDirs.size} directorio(s) libs/")

        val local = jarCollector.collect(input.libsDirs)
        ctx.log(
            LogLevel.INFO, "Resolve",
            "Encontrados ${local.jars.size} .jar y ${local.aars.size} .aar"
        )

        val allJars = local.jars.toMutableList()
        val allRes = mutableListOf<File>()
        val allManifests = mutableListOf<File>()

        local.aars.forEachIndexed { index, aar ->
            ctx.checkCancelled()
            ctx.emit(
                BuildState.ResolvingDeps(
                    "Extrayendo ${aar.name}",
                    done = index + 1,
                    total = local.aars.size
                )
            )
            val extracted = aarExtractor.extract(aar, ctx.cacheDir)
            extracted.classesJar?.let { allJars.add(it) }
            allJars.addAll(extracted.innerJars)
            extracted.resDir?.let { allRes.add(it) }
            extracted.manifest?.let { allManifests.add(it) }
        }

        val result = ResolvedClasspath(
            jars = allJars.distinct(),
            aarResources = allRes,
            aarManifests = allManifests
        )

        ctx.log(
            LogLevel.INFO, "Resolve",
            "Resuelto: ${result.jars.size} jar(s), ${result.aarResources.size} res/, ${result.aarManifests.size} manifiesto(s)"
        )
        return result
    }
}
