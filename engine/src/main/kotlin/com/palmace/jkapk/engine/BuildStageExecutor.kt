package com.palmace.jkapk.engine

interface BuildStageExecutor<I, O> {
    val stage: BuildStage
    suspend fun execute(input: I, ctx: BuildContext): O
}
