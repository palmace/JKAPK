package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildStage
import com.palmace.jkapk.engine.BuildStageExecutor
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel

/**
 * Stub Fase 2. En Fase 7 se ensambla el APK (recursos + .dex + manifiesto).
 */
internal class PackageStage : BuildStageExecutor<Any?, Any?> {
    override val stage: BuildStage = BuildStage.PACKAGE

    override suspend fun execute(input: Any?, ctx: BuildContext): Any? {
        ctx.emit(BuildState.Packaging("Stub empaquetado"))
        ctx.log(LogLevel.INFO, "Package", "Stub: sin APK que empaquetar")
        return input
    }
}
