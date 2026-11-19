package com.palmace.jkapk.engine.internal.stage

import com.android.apksig.ApkVerifier
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

class SignStageTest {

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

    private fun fakeConfig(minSdk: Int = 24): ProjectConfig = ProjectConfig(
        projectName = "TestApp",
        projectRoot = tmp,
        javaSourceDirs = emptyList(),
        kotlinSourceDirs = emptyList(),
        resourceDir = null,
        assetDir = null,
        manifestFile = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest/>") },
        packageName = "com.test",
        compileSdk = 34,
        minSdk = minSdk,
        buildOutputDir = File(tmp, "build").apply { mkdirs() },
        androidJar = File(tmp, "android.jar")
    )

    private fun makeUnsignedApk(): File {
        val apk = File(tmp, "unsigned.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<binary-manifest/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(byteArrayOf(0x02, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x00))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00))
            zip.closeEntry()
        }
        return apk
    }

    @Test
    fun `firma apk con debug keystore y apksig verifica`() = runTest {
        val apk = makeUnsignedApk()
        val config = fakeConfig(minSdk = 24)
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = SignStage().execute(
            ResolvedClasspath(jars = emptyList(), unsignedApk = apk),
            fakeCtx(config, diagnostics)
        )

        assertNotNull(result.signedApk)
        assertTrue(result.signedApk!!.exists())

        // Verificar bloque de firma APK Signature Scheme v2/v3 ("APK Sig Block 42")
        val signedBytes = result.signedApk!!.readBytes()
        val tail = String(signedBytes.takeLast(4096).toByteArray(), Charsets.ISO_8859_1)
        assertTrue(
            tail.contains("APK Sig Block 42"),
            "El APK firmado debe contener el bloque v2/v3 'APK Sig Block 42'"
        )
    }

    @Test
    fun `minSdk 24 no incluye v1 JAR signature`() = runTest {
        val apk = makeUnsignedApk()
        val config = fakeConfig(minSdk = 24)
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = SignStage().execute(
            ResolvedClasspath(jars = emptyList(), unsignedApk = apk),
            fakeCtx(config, diagnostics)
        )

        ZipFile(result.signedApk!!).use { zip ->
            val hasV1 = zip.entries().asSequence().any { entry ->
                entry.name.startsWith("META-INF/") &&
                    (entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") || entry.name.endsWith(".EC"))
            }
            assertTrue(!hasV1, "minSdk=24 no debe incluir firma v1 (META-INF/*.RSA)")
        }
    }

    @Test
    fun `minSdk 21 incluye v1 JAR signature`() = runTest {
        val apk = makeUnsignedApk()
        val config = fakeConfig(minSdk = 21)
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = SignStage().execute(
            ResolvedClasspath(jars = emptyList(), unsignedApk = apk),
            fakeCtx(config, diagnostics)
        )

        ZipFile(result.signedApk!!).use { zip ->
            val hasV1 = zip.entries().asSequence().any { entry ->
                entry.name.startsWith("META-INF/") &&
                    (entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") || entry.name.endsWith(".EC"))
            }
            assertTrue(hasV1, "minSdk=21 debe incluir firma v1 (META-INF/*.RSA)")
        }
    }

    @Test
    fun `sin unsigned apk lanza SignExecutionException`() = runTest {
        val config = fakeConfig()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        assertThrows<SignExecutionException> {
            kotlinx.coroutines.runBlocking {
                SignStage().execute(
                    ResolvedClasspath(jars = emptyList(), unsignedApk = null),
                    fakeCtx(config, diagnostics)
                )
            }
        }
    }
}
