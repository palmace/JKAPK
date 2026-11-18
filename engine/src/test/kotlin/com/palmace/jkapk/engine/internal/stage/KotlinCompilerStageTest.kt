package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KotlinCompilerStageTest {

    @TempDir
    lateinit var tmp: File

    private fun fakeCtx(config: ProjectConfig): BuildContext = object : BuildContext {
        override val config = config
        override val workDir = File(tmp, "work").apply { mkdirs() }
        override val cacheDir = File(tmp, "cache").apply { mkdirs() }
        override val nativeLibDir = File(tmp, "native").apply { mkdirs() }
        override var cancelled = false
        override fun emit(state: BuildState) {}
        override fun log(level: LogLevel, tag: String, message: String) {}
        override fun diagnostic(diagnostic: BuildDiagnostic) {}
        override fun checkCancelled() {}
    }

    private fun fakeConfig(): ProjectConfig {
        val kotlinSrc = File(tmp, "src/main/kotlin").apply { mkdirs() }
        return ProjectConfig(
            projectName = "Test",
            projectRoot = tmp,
            javaSourceDirs = emptyList(),
            kotlinSourceDirs = listOf(kotlinSrc),
            resourceDir = null,
            assetDir = null,
            manifestFile = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest/>") },
            packageName = "com.test",
            compileSdk = 34,
            minSdk = 24,
            buildOutputDir = File(tmp, "build").apply { mkdirs() }
        )
    }

    @Test
    fun `sin kotlin no compila`() = runTest {
        val config = fakeConfig()
        val stage = KotlinCompilerStage()
        val result = stage.execute(ResolvedClasspath(emptyList()), fakeCtx(config))
        assertTrue(result.jars.isEmpty())
    }

    @Test
    fun `compila un kt trivial y agrega output a jars`() = runTest {
        val config = fakeConfig()
        val srcDir = config.kotlinSourceDirs.first()
        File(srcDir, "Hello.kt").writeText(
            """
            package com.test
            class Hello { fun greet() = "hi" }
            """.trimIndent()
        )

        val stage = KotlinCompilerStage()
        val result = stage.execute(ResolvedClasspath(emptyList()), fakeCtx(config))

        val kotlinOutput = File(tmp, "work/classes/kotlin")
        assertTrue(kotlinOutput.isDirectory, "Debe existir workDir/classes/kotlin")
        assertTrue(
            result.jars.any { it.absolutePath == kotlinOutput.absolutePath },
            "El output de Kotlin debe estar en jars para Fase 4b"
        )
    }
}
