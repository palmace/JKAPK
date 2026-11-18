package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.Severity
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JavaCompilerStageTest {

    @TempDir
    lateinit var tmp: File

    private fun fakeCtx(config: ProjectConfig, diagnostics: MutableList<BuildDiagnostic>): BuildContext =
        object : BuildContext {
            override val config = config
            override val workDir = File(tmp, "work").apply { mkdirs() }
            override val cacheDir = File(tmp, "cache").apply { mkdirs() }
            override val nativeLibDir = File(tmp, "native").apply { mkdirs() }
            override var cancelled = false
            override fun emit(state: BuildState) {}
            override fun log(level: LogLevel, tag: String, message: String) {}
            override fun diagnostic(diagnostic: BuildDiagnostic) { diagnostics.add(diagnostic) }
            override fun checkCancelled() {}
        }

    private fun fakeConfig(): ProjectConfig {
        val javaSrc = File(tmp, "src/main/java").apply { mkdirs() }
        return ProjectConfig(
            projectName = "Test",
            projectRoot = tmp,
            javaSourceDirs = listOf(javaSrc),
            kotlinSourceDirs = emptyList(),
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
    fun `sin java no compila`() = runTest {
        val config = fakeConfig()
        val stage = JavaCompilerStage()
        val result = stage.execute(ResolvedClasspath(emptyList()), fakeCtx(config, mutableListOf()))
        assertTrue(result.jars.isEmpty())
    }

    @Test
    fun `compila un java trivial y agrega output a jars`() = runTest {
        val config = fakeConfig()
        val srcDir = config.javaSourceDirs.first()
        File(srcDir, "Hello.java").writeText(
            """
            package com.test;
            public class Hello { public String greet() { return "hi"; } }
            """.trimIndent()
        )

        val diagnostics = mutableListOf<BuildDiagnostic>()
        val stage = JavaCompilerStage()
        val result = stage.execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))

        val javaOutput = File(tmp, "work/classes/java")
        assertTrue(javaOutput.isDirectory, "Debe existir workDir/classes/java")
        assertTrue(
            result.jars.any { it.absolutePath == javaOutput.absolutePath },
            "El output de Java debe estar en jars"
        )
    }

    @Test
    fun `java con error produce diagnostic ERROR`() = runTest {
        val config = fakeConfig()
        val srcDir = config.javaSourceDirs.first()
        File(srcDir, "Broken.java").writeText(
            """
            package com.test;
            public class Broken {
                public void x() { int y = "not an int"; }
            }
            """.trimIndent()
        )

        val diagnostics = mutableListOf<BuildDiagnostic>()
        val stage = JavaCompilerStage()

        try {
            stage.execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))
        } catch (_: JavaCompilationException) {
            // esperado
        }

        assertTrue(
            diagnostics.any { it.severity == Severity.ERROR },
            "Debe haber al menos un diagnostico ERROR"
        )
    }
}
