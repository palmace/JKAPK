package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel

/**
 * Stub Fase 2. En Fase 5 se integra AAPT2 binario ARM64 empaquetado
 * y se genera R.java en el sourcePath de Java.
 */
internal class Aapt2Stage : BuildStageExecutor<Any?, Any?> {
    override val stage: BuildStage = BuildStage.RESOURCES

    override suspend fun execute(input: Any?, ctx: BuildContext): Any? {
        ctx.emit(BuildState.ProcessingResources("Stub AAPT2"))
        ctx.log(LogLevel.INFO, "AAPT2", "Stub: sin recursos reales")
        return input
    }
}
