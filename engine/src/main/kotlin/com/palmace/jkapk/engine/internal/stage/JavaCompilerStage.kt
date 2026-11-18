package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel

/**
 * Stub Fase 2. En Fase 3 se integra nb-javac / Eclipse JDT como librería.
 */
internal class JavaCompilerStage : BuildStageExecutor<Any?, Any?> {
    override val stage: BuildStage = BuildStage.JAVA

    override suspend fun execute(input: Any?, ctx: BuildContext): Any? {
        ctx.emit(BuildState.CompilingJava("(stub)", 0, 0))
        ctx.log(LogLevel.INFO, "JavaCompiler", "Stub: sin archivos Java reales")
        return input
    }
}
