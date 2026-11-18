package com.palmace.jkapk.engine.internal.resolve

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.JavaVersion
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.internal.stage.DependencyResolverStage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DependencyResolverStageTest {

    @TempDir
    lateinit var tmp: File

    private fun fakeCtx(cacheDir: File): BuildContext = object : BuildContext {
        override val workDir = File(tmp, "work").apply { mkdirs() }
        override val cacheDir = cacheDir
        override val nativeLibDir = File(tmp, "native").apply { mkdirs() }
        override var cancelled = false
        override fun emit(state: BuildState) {}
        override fun log(level: LogLevel, tag: String, message: String) {}
        override fun diagnostic(diagnostic: BuildDiagnostic) {}
        override fun checkCancelled() {}
    }

    private fun fakeConfig(libsDirs: List<File>): ProjectConfig = ProjectConfig(
        projectName = "Test",
        projectRoot = tmp,
        javaSourceDirs = emptyList(),
        kotlinSourceDirs = emptyList(),
        resourceDir = null,
        assetDir = null,
        manifestFile = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest/>") },
        libsDirs = libsDirs,
        packageName = "com.test",
        compileSdk = 34,
        minSdk = 24,
        buildOutputDir = File(tmp, "build").apply { mkdirs() }
    )

    private fun makeAar(dir: File, name: String): File {
        val aar = File(dir, name)
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.jar"))
            val inner = java.io.ByteArrayOutputStream()
            ZipOutputStream(inner).use { innerZip ->
                innerZip.putNextEntry(ZipEntry("Dummy.class"))
                innerZip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
                innerZip.closeEntry()
            }
            zip.write(inner.toByteArray())
            zip.closeEntry()
        }
        return aar
    }

    @Test
    fun `resuelve jars y aars mezclados`() = runTest {
        val libs = File(tmp, "libs").apply { mkdirs() }
        File(libs, "plain.jar").writeText("dummy")
        makeAar(libs, "lib.aar")

        val config = fakeConfig(listOf(libs))
        val cache = File(tmp, "cache").apply { mkdirs() }
        val stage = DependencyResolverStage()

        val result = stage.execute(config, fakeCtx(cache))

        // 1 jar plano + classes.jar extraído del aar = 2
        assertEquals(2, result.jars.size)
        assertTrue(result.jars.all { it.exists() }, "Todos los jars deben existir")
        assertEquals(1, result.aarManifests.size)
    }

    @Test
    fun `sin libs produce classpath vacio`() = runTest {
        val config = fakeConfig(emptyList())
        val cache = File(tmp, "cache").apply { mkdirs() }

        val result = DependencyResolverStage().execute(config, fakeCtx(cache))

        assertTrue(result.isEmpty)
    }
}
