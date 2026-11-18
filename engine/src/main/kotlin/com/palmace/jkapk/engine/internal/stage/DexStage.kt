package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel

/**
 * Stub Fase 2. En Fase 6 se invoca la API programática de D8/R8
 * para generar .dex fragmentados (multidex).
 */
internal class DexStage : BuildStageExecutor<Any?, Any?> {
    override val stage: BuildStage = BuildStage.DEX

    override suspend fun execute(input: Any?, ctx: BuildContext): Any? {
        ctx.emit(BuildState.Dexing(1, 1))
        ctx.log(LogLevel.INFO, "D8", "Stub: sin clases reales que dexificar")
        return input
    }
}
