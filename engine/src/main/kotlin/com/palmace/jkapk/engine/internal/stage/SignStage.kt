package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel

/**
 * Stub Fase 2. En Fase 8 se firma con apksigner en Java puro
 * (sin binarios externos).
 */
internal class SignStage : BuildStageExecutor<Any?, Any?> {
    override val stage: BuildStage = BuildStage.SIGN

    override suspend fun execute(input: Any?, ctx: BuildContext): Any? {
        ctx.emit(BuildState.Signing("(stub)"))
        ctx.log(LogLevel.INFO, "Sign", "Stub: sin APK que firmar")
        return input
    }
}
