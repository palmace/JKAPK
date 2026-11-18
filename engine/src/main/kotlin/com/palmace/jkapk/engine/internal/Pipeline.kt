package com.palmace.jkapk.engine.internal

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStageExecutor
import kotlinx.coroutines.CancellationException

/**
 * Orquestador secuencial de etapas.
 *
 * El cast a Any? es intencional: cada etapa tiene tipos I/O distintos y el
 * pipeline no necesita conocerlos. En fases posteriores, cuando los outputs
 * sean artefactos reales, el cast seguirá siendo seguro por construcción
 * (cada etapa declara su contrato en su firma).
 */
internal class Pipeline(
    private val stages: List<BuildStageExecutor<*, *>>
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun execute(initialInput: Any?, ctx: BuildContext): Any? {
        var current: Any? = initialInput
        for (stage in stages) {
            ctx.checkCancelled()
            val executor = stage as BuildStageExecutor<Any?, Any?>
            current = executor.execute(current, ctx)
        }
        return current
    }
}
