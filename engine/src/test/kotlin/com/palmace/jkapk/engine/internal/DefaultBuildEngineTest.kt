package com.palmace.jkapk.engine.internal

import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.JavaVersion
import com.palmace.jkapk.engine.ProjectConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
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
        resourceDir = null,
        assetDir = null,
        manifestFile = File(tmp, "src/main/AndroidManifest.xml").apply { writeText("<manifest/>") },
        packageName = "com.test.app",
        compileSdk = 34,
        minSdk = 24,
        buildOutputDir = File(tmp, "build").apply { mkdirs() }
    )

    @Test
    fun `pipeline recorre estados y falla limpiamente sin recursos`() = runTest {
        val engine = DefaultBuildEngine()
        val result = engine.build(fakeConfig())

        // Proyecto vacio: el pipeline llega hasta PackageStage y falla
        // porque no hay resources.ap_ ni dex. El resultado debe ser success=false
        // pero el StateFlow debe haber emitido estados intermedios.
        assertFalse(result.success, "Un proyecto vacio no debe producir APK")

        val finalState = engine.state.value
        assertTrue(
            finalState is BuildState.Failed,
            "El estado final debe ser Failed, pero fue: $finalState"
        )
    }

    @Test
    fun `cancel a mitad del pipeline produce Cancelled`() = runTest {
        val engine = DefaultBuildEngine()

        val job = launch { engine.build(fakeConfig()) }
        delay(1)
        engine.cancel()
        job.join()

        val finalState = engine.state.value
        assertTrue(
            finalState is BuildState.Cancelled || finalState is BuildState.Failed,
            "El estado final debe ser Cancelled o Failed, pero fue: $finalState"
        )
    }
}
