package com.palmace.jkapk.engine.internal

import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.JavaVersion
import com.palmace.jkapk.engine.ProjectConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBuildEngineTest {

    @TempDir
    lateinit var tmp: File

    private fun fakeConfig(): ProjectConfig = ProjectConfig(
        projectName = "TestApp",
        projectRoot = tmp,
        javaSourceDirs = listOf(File(tmp, "src/main/java").apply { mkdirs() }),
        kotlinSourceDirs = listOf(File(tmp, "src/main/kotlin").apply { mkdirs() }),
        resourceDir = File(tmp, "src/main/res").apply { mkdirs() },
        assetDir = null,
        manifestFile = File(tmp, "src/main/AndroidManifest.xml").apply { writeText("<manifest/>") },
        packageName = "com.test.app",
        compileSdk = 34,
        minSdk = 24,
        buildOutputDir = File(tmp, "build").apply { mkdirs() }
    )

    @Test
    fun `build exitoso pasa por todos los estados en orden`() = runTest {
        val engine = DefaultBuildEngine()
        val states = mutableListOf<BuildState>()

        val collector = launch {
            engine.state.toList(states)
        }

        val result = engine.build(fakeConfig())
        yield()
        collector.cancel()

        assertTrue(result.success, "El build debe ser exitoso")
        assertTrue(result.apk != null, "Debe existir un APK")
        assertTrue(
            states.any { it is BuildState.Success },
            "StateFlow debe haber emitido Success"
        )
    }

    @Test
    fun `cancel a mitad del pipeline produce Cancelled`() = runTest {
        val engine = DefaultBuildEngine()

        val job = launch { engine.build(fakeConfig()) }
        // Dar un tick para que arranque y active el contexto
        delay(1)
        engine.cancel()
        job.join()

        assertEquals(
            BuildState.Cancelled,
            engine.state.value,
            "El estado final debe ser Cancelled"
        )
    }
}
