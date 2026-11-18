package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel

/**
 * Stub Fase 2. En Fase 4 se integra kotlinc como librería.
 */
internal class KotlinCompilerStage : BuildStageExecutor<Any?, Any?> {
    override val stage: BuildStage = BuildStage.KOTLIN

    override suspend fun execute(input: Any?, ctx: BuildContext): Any? {
        ctx.emit(BuildState.CompilingKotlin("(stub)", 0, 0))
        ctx.log(LogLevel.INFO, "KotlinCompiler", "Stub: sin archivos Kotlin reales")
        return input
    }
}
