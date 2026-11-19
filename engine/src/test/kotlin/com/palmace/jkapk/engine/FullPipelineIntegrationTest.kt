package com.palmace.jkapk.engine

import com.android.apksig.ApkVerifier
import com.palmace.jkapk.engine.internal.DefaultBuildEngine
import com.palmace.jkapk.engine.internal.DiagnosticSourceMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class FullPipelineIntegrationTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `SourceRefMapper normaliza rutas dentro de projectRoot y workDir`() {
        val projectRoot = File(tmp, "app").apply { mkdirs() }
        val workDir = File(tmp, "work").apply { mkdirs() }
        val sourceFile = File(projectRoot, "src/main/kotlin/com/example/Main.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example")
        }

        val ctx = object : BuildContext {
            override val config = ProjectConfig(
                projectName = "MapperTest",
                projectRoot = projectRoot,
                javaSourceDirs = emptyList(),
                kotlinSourceDirs = emptyList(),
                resourceDir = null,
                assetDir = null,
                manifestFile = File(projectRoot, "AndroidManifest.xml"),
                packageName = "com.example",
                compileSdk = 34,
                minSdk = 24,
                buildOutputDir = File(tmp, "out")
            )
            override val workDir = workDir
            override val cacheDir = File(tmp, "cache")
            override val nativeLibDir = File(tmp, "native")
            override val cancelled = false
            override fun emit(state: BuildState) {}
            override fun log(level: LogLevel, tag: String, message: String) {}
            override fun diagnostic(diagnostic: BuildDiagnostic) {}
            override fun checkCancelled() {}
        }

        val ref = DiagnosticSourceMapper.fromPath(ctx, sourceFile.absolutePath, line = 42, column = 13)
        assertNotNull(ref)
        assertEquals(projectRoot, ref!!.projectRoot)
        assertEquals("src/main/kotlin/com/example/Main.kt", ref.relativePath)
        assertEquals(42, ref.line)
        assertEquals(13, ref.column)
    }

    @Test
    fun `pipeline completo genera y verifica APK cuando aapt2 y android jar estan disponibles`() = runTest {
        val aapt2 = findWorkingAapt2()
        val androidJar = findAndroidJar()
        assumeTrue(aapt2 != null && androidJar != null, "Requiere aapt2 funcional y android.jar en el entorno")

        val projectRoot = File(tmp, "SampleApp").apply { mkdirs() }
        val javaSrc = File(projectRoot, "src/main/java/com/palmace/sample").apply { mkdirs() }
        val ktSrc = File(projectRoot, "src/main/kotlin/com/palmace/sample").apply { mkdirs() }
        val resValues = File(projectRoot, "src/main/res/values").apply { mkdirs() }

        File(resValues, "strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">SampleJKAPK</string>
            </resources>
            """.trimIndent()
        )

        val manifest = File(projectRoot, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.palmace.sample">
                    <application android:label="@string/app_name" />
                </manifest>
                """.trimIndent()
            )
        }

        File(javaSrc, "GreeterHelper.java").writeText(
            """
            package com.palmace.sample;

            public class GreeterHelper {
                public String buildGreeting(String name) {
                    return "Hola, " + name;
                }
            }
            """.trimIndent()
        )

        File(ktSrc, "AppEntry.kt").writeText(
            """
            package com.palmace.sample

            class AppEntry {
                fun run(): String {
                    val helper = GreeterHelper()
                    return helper.buildGreeting("OMNi")
                }
            }
            """.trimIndent()
        )

        val config = ProjectConfig(
            projectName = "SampleApp",
            projectRoot = projectRoot,
            javaSourceDirs = listOf(File(projectRoot, "src/main/java")),
            kotlinSourceDirs = listOf(File(projectRoot, "src/main/kotlin")),
            resourceDir = File(projectRoot, "src/main/res"),
            assetDir = null,
            manifestFile = manifest,
            packageName = "com.palmace.sample",
            compileSdk = 34,
            minSdk = 24,
            buildOutputDir = File(tmp, "build-out").apply { mkdirs() },
            androidJar = androidJar!!,
            aapt2Binary = aapt2
        )

        val result = DefaultBuildEngine().build(config)
        assertTrue(result.success, "El build completo debe ser exitoso. Diagnosticos: ${result.diagnostics}")
        assertNotNull(result.apk)
        assertTrue(result.apk!!.exists())

        ZipFile(result.apk!!).use { zip ->
            assertNotNull(zip.getEntry("AndroidManifest.xml"), "Falta AndroidManifest.xml en APK")
            assertNotNull(zip.getEntry("classes.dex"), "Falta classes.dex en APK")
            assertNotNull(zip.getEntry("resources.arsc"), "Falta resources.arsc en APK")
        }

        val verifier = ApkVerifier.Builder(result.apk!!).build()
        val verifyResult = verifier.verify()
        assertTrue(verifyResult.isVerified, "ApkVerifier rechazo el APK: ${verifyResult.errors}")
    }

    private fun findWorkingAapt2(): File? {
        val candidates = buildList {
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
                add(File(dir, "aapt2"))
            }
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            if (sdk != null) {
                File(sdk, "build-tools").listFiles()
                    ?.sortedByDescending { it.name }
                    ?.forEach { add(File(it, "aapt2")) }
            }
        }
        return candidates.firstOrNull { bin ->
            if (!bin.exists() || !bin.canExecute()) return@firstOrNull false
            runCatching {
                val proc = ProcessBuilder(bin.absolutePath, "version").start()
                proc.waitFor() == 0
            }.getOrDefault(false)
        }
    }

    private fun findAndroidJar(): File? {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        val platforms = File(sdk, "platforms")
        if (!platforms.isDirectory) return null
        return platforms.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { File(it, "android.jar") }
            ?.firstOrNull { it.exists() }
    }
}
