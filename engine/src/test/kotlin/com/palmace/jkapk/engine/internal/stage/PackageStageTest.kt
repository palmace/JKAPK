package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PackageStageTest {

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

    private fun fakeConfig(assetsDir: File? = null): ProjectConfig = ProjectConfig(
        projectName = "Test",
        projectRoot = tmp,
        javaSourceDirs = emptyList(),
        kotlinSourceDirs = emptyList(),
        resourceDir = null,
        assetDir = assetsDir,
        manifestFile = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest package=\"com.test\"/>") },
        packageName = "com.test",
        compileSdk = 34,
        minSdk = 24,
        buildOutputDir = File(tmp, "build").apply { mkdirs() },
        androidJar = File(tmp, "android.jar")
    )

    private fun makeResourcesAp(withManifest: Boolean = true): File {
        val ap = File(tmp, "resources.ap_")
        ZipOutputStream(ap.outputStream()).use { zip ->
            if (withManifest) {
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write("<binary-manifest/>".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(byteArrayOf(0x02, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x00))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("res/layout/main.xml"))
            zip.write("<layout/>".toByteArray())
            zip.closeEntry()
        }
        return ap
    }

    private fun makeDex(name: String): File {
        val dex = File(tmp, name)
        dex.writeBytes(byteArrayOf(
            0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00,
            0x00, 0x00, 0x00, 0x00
        ))
        return dex
    }

    @Test
    fun `empaqueta apk con manifest, arsc, res y dex`() = runTest {
        val resourcesAp = makeResourcesAp()
        val dex = makeDex("classes.dex")
        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = PackageStage().execute(
            ResolvedClasspath(jars = emptyList(), compiledResourcesApk = resourcesAp, dexFiles = listOf(dex)),
            fakeCtx(config, diagnostics)
        )

        assertNotNull(result.unsignedApk)
        assertTrue(result.unsignedApk!!.exists())

        ZipFile(result.unsignedApk!!).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("AndroidManifest.xml" in names, "Debe tener manifest: $names")
            assertTrue("resources.arsc" in names, "Debe tener arsc: $names")
            assertTrue("classes.dex" in names, "Debe tener classes.dex: $names")
            assertTrue("res/layout/main.xml" in names, "Debe tener res/: $names")

            val arsc = zip.getEntry("resources.arsc")
            assertTrue(
                arsc.method == ZipEntry.STORED,
                "resources.arsc debe ser STORED (Android 11+), method=${arsc.method}"
            )
        }
    }

    @Test
    fun `multidex se empaqueta como classes dex y classes2 dex`() = runTest {
        val resourcesAp = makeResourcesAp()
        val dex1 = makeDex("a.dex")
        val dex2 = makeDex("b.dex")
        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = PackageStage().execute(
            ResolvedClasspath(jars = emptyList(), compiledResourcesApk = resourcesAp, dexFiles = listOf(dex1, dex2)),
            fakeCtx(config, diagnostics)
        )

        ZipFile(result.unsignedApk!!).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("classes.dex" in names, "Falta classes.dex: $names")
            assertTrue("classes2.dex" in names, "Falta classes2.dex: $names")
        }
    }

    @Test
    fun `assets se empaqueta en carpeta assets`() = runTest {
        val resourcesAp = makeResourcesAp()
        val dex = makeDex("classes.dex")
        val assetsDir = File(tmp, "assets").apply { mkdirs() }
        File(assetsDir, "data.json").writeText("{}")
        val config = fakeConfig(assetsDir = assetsDir)
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = PackageStage().execute(
            ResolvedClasspath(jars = emptyList(), compiledResourcesApk = resourcesAp, dexFiles = listOf(dex)),
            fakeCtx(config, diagnostics)
        )

        ZipFile(result.unsignedApk!!).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("assets/data.json" in names, "Falta assets/: $names")
        }
    }

    @Test
    fun `sin manifest en resources ap_ lanza PackageExecutionException`() = runTest {
        val resourcesAp = makeResourcesAp(withManifest = false)
        val dex = makeDex("classes.dex")
        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        assertThrows<PackageExecutionException> {
            kotlinx.coroutines.runBlocking {
                PackageStage().execute(
                    ResolvedClasspath(jars = emptyList(), compiledResourcesApk = resourcesAp, dexFiles = listOf(dex)),
                    fakeCtx(config, diagnostics)
                )
            }
        }
    }

    @Test
    fun `sin resources ap_ lanza PackageExecutionException`() = runTest {
        val dex = makeDex("classes.dex")
        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        assertThrows<PackageExecutionException> {
            kotlinx.coroutines.runBlocking {
                PackageStage().execute(
                    ResolvedClasspath(jars = emptyList(), compiledResourcesApk = null, dexFiles = listOf(dex)),
                    fakeCtx(config, diagnostics)
                )
            }
        }
    }
}
