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
import javax.tools.ToolProvider

class DexStageTest {

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

    private fun makeEmptyJar(file: File): File {
        file.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun fakeConfig(androidJar: File? = null): ProjectConfig = ProjectConfig(
        projectName = "Test",
        projectRoot = tmp,
        javaSourceDirs = emptyList(),
        kotlinSourceDirs = emptyList(),
        resourceDir = null,
        assetDir = null,
        manifestFile = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest package=\"com.test\"/>") },
        packageName = "com.test",
        compileSdk = 34,
        minSdk = 24,
        buildOutputDir = File(tmp, "build").apply { mkdirs() },
        androidJar = androidJar ?: makeEmptyJar(File(tmp, "android.jar"))
    )

    @Test
    fun `sin clases hace early return`() = runTest {
        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = DexStage().execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))

        assertTrue(result.dexFiles.isEmpty())
    }

    @Test
    fun `compila un java con javac y lo dexifica`() = runTest {
        // 1. Compilar un .java a .class con javac
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val javaFile = File(srcDir, "Hello.java").apply {
            writeText(
                """
                package com.test;
                public class Hello { public String greet() { return "hi"; } }
                """.trimIndent()
            )
        }
        val classesDir = File(tmp, "classes").apply { mkdirs() }

        val compiler = ToolProvider.getSystemJavaCompiler()
        val fm = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
        val units = fm.getJavaFileObjectsFromFiles(listOf(javaFile))
        val task = compiler.getTask(
            null, fm, null,
            listOf("-d", classesDir.absolutePath),
            null, units
        )
        assertTrue(task.call(), "javac debe compilar el archivo de prueba")

        // 2. Dexificar con D8
        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = DexStage().execute(
            ResolvedClasspath(jars = listOf(classesDir)),
            fakeCtx(config, diagnostics)
        )

        assertTrue(result.dexFiles.isNotEmpty(), "Debe haber al menos un .dex")
        val dex = result.dexFiles.first()
        assertTrue(dex.exists())
        assertTrue(dex.extension == "dex")

        // Magic header: dex\n035\0 o dex\n039\0
        val header = dex.readBytes().take(8).toByteArray()
        val headerStr = String(header, Charsets.US_ASCII)
        assertTrue(
            headerStr.startsWith("dex\n"),
            "El archivo .dex debe empezar con 'dex\\n', pero empieza con: $headerStr"
        )
    }

    @Test
    fun `class corrupto lanza DexExecutionException o diagnostic de error`() = runTest {
        val classesDir = File(tmp, "classes").apply { mkdirs() }
        File(classesDir, "Broken.class").writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        var thrown: Throwable? = null
        try {
            DexStage().execute(
                ResolvedClasspath(jars = listOf(classesDir)),
                fakeCtx(config, diagnostics)
            )
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown, "Un .class corrupto debe lanzar excepcion")
        assertTrue(
            thrown is DexExecutionException,
            "Debe ser DexExecutionException, pero fue: ${thrown!!::class.simpleName}"
        )
    }
}
