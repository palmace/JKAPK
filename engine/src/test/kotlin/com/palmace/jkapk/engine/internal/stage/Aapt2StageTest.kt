package com.palmace.jkapk.engine.internal.stage

import com.palmace.jkapk.engine.BuildContext
import com.palmace.jkapk.engine.BuildDiagnostic
import com.palmace.jkapk.engine.BuildState
import com.palmace.jkapk.engine.LogLevel
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.Severity
import com.palmace.jkapk.engine.internal.resolve.ResolvedClasspath
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class Aapt2StageTest {

    @TempDir
    lateinit var tmp: File

    private fun makeFakeAapt2(): File {
        val script = File(tmp, "fake-aapt2.sh")
        val shebang = if (System.getProperty("os.name").lowercase().contains("android")) {
            "#!/data/data/com.termux/files/usr/bin/bash"
        } else {
            "#!/usr/bin/env bash"
        }
        script.writeText(
            """
            ${shebang}
            set -e
            CMD="${'$'}1"
            shift
            case "${'$'}CMD" in
              version)
                echo "Android Asset Packaging Tool (aapt) 2:16.0.0-fake"
                exit 0
                ;;
              compile)
                OUT_ZIP=""
                while [[ ${'$'}# -gt 0 ]]; do
                  case "${'$'}1" in
                    --dir) DIR="${'$'}2"; shift 2;;
                    -o) OUT_ZIP="${'$'}2"; shift 2;;
                    *) shift;;
                  esac
                done
                mkdir -p "${'$'}(dirname "${'$'}OUT_ZIP")"
                echo "fake-flat" > "${'$'}OUT_ZIP"
                exit 0
                ;;
              link)
                OUT_APK=""; MANIFEST=""; JAVA_DIR=""
                while [[ ${'$'}# -gt 0 ]]; do
                  case "${'$'}1" in
                    -o) OUT_APK="${'$'}2"; shift 2;;
                    --manifest) MANIFEST="${'$'}2"; shift 2;;
                    --java) JAVA_DIR="${'$'}2"; shift 2;;
                    *) shift;;
                  esac
                done
                PKG="${'$'}(grep -oE 'package="[^"]+"' "${'$'}MANIFEST" | head -1 | sed 's/package="//;s/"//')"
                [ -z "${'$'}PKG" ] && PKG="com.fake"
                mkdir -p "${'$'}(dirname "${'$'}OUT_APK")"
                echo "fake-apk" > "${'$'}OUT_APK"
                if [ -n "${'$'}JAVA_DIR" ]; then
                  PKG_PATH="${'$'}(echo "${'$'}PKG" | tr '.' '/')"
                  mkdir -p "${'$'}JAVA_DIR/${'$'}PKG_PATH"
                  echo "package ${'$'}PKG; public final class R {}" > "${'$'}JAVA_DIR/${'$'}PKG_PATH/R.java"
                fi
                exit 0
                ;;
            esac
            exit 1
            """.trimIndent()
        )
        script.setExecutable(true)
        return script
    }

    private fun fakeCtx(
        config: ProjectConfig,
        diagnostics: MutableList<BuildDiagnostic>
    ): BuildContext = object : BuildContext {
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

    private fun fakeConfig(
        resDir: File?,
        manifest: File,
        androidJar: File,
        aapt2Binary: File? = null
    ): ProjectConfig = ProjectConfig(
        projectName = "Test",
        projectRoot = tmp,
        javaSourceDirs = emptyList(),
        kotlinSourceDirs = emptyList(),
        resourceDir = resDir,
        assetDir = null,
        manifestFile = manifest,
        packageName = "com.test",
        compileSdk = 34,
        minSdk = 24,
        buildOutputDir = File(tmp, "build").apply { mkdirs() },
        androidJar = androidJar,
        aapt2Binary = aapt2Binary
    )

    @Test
    fun `sin recursos hace early return`() = runTest {
        val manifest = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest package=\"com.test\"/>") }
        val androidJar = File(tmp, "android.jar").apply { writeText("fake") }
        val config = fakeConfig(resDir = null, manifest = manifest, androidJar = androidJar)
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = Aapt2Stage().execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))

        assertTrue(result.generatedJavaDir == null, "Sin recursos no debe generar R.java")
        assertTrue(result.compiledResourcesApk == null)
    }

    @Test
    fun `con recursos pero sin aapt2 funcional lanza excepcion de aapt2`() = runTest {
        val resDir = File(tmp, "src/main/res").apply { mkdirs() }
        File(resDir, "values").mkdirs()
        File(resDir, "values/strings.xml").writeText("<resources/>")
        val manifest = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest package=\"com.test\"/>") }
        val androidJar = File(tmp, "android.jar").apply { writeText("fake") }
        val config = fakeConfig(
            resDir = resDir,
            manifest = manifest,
            androidJar = androidJar,
            aapt2Binary = File(tmp, "no-existe-aapt2")
        )
        val diagnostics = mutableListOf<BuildDiagnostic>()

        var thrown: Throwable? = null
        try {
            Aapt2Stage().execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown, "Debe lanzar una excepcion de aapt2")
        assertTrue(
            thrown is Aapt2UnavailableException || thrown is Aapt2ExecutionException,
            "Debe ser Aapt2UnavailableException o Aapt2ExecutionException, pero fue: " +
                thrown!!::class.simpleName
        )
    }

    @Test
    fun `con fake aapt2 genera R java y resources ap_`() = runTest {
        val resDir = File(tmp, "src/main/res").apply { mkdirs() }
        File(resDir, "values").mkdirs()
        File(resDir, "values/strings.xml").writeText("<resources/>")
        val manifest = File(tmp, "AndroidManifest.xml").apply {
            writeText("<manifest package=\"com.test.app\"/>")
        }
        val androidJar = File(tmp, "android.jar").apply { writeText("fake") }
        val fakeAapt2 = makeFakeAapt2()

        val config = fakeConfig(
            resDir = resDir,
            manifest = manifest,
            androidJar = androidJar,
            aapt2Binary = fakeAapt2
        )
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = Aapt2Stage().execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))

        assertNotNull(result.generatedJavaDir)
        assertTrue(result.generatedJavaDir!!.isDirectory)
        assertNotNull(result.compiledResourcesApk)
        assertTrue(result.compiledResourcesApk!!.exists())

        val rJava = File(result.generatedJavaDir!!, "com/test/app/R.java")
        assertTrue(rJava.exists(), "R.java debe existir en ${rJava.absolutePath}")
        assertTrue(rJava.readText().contains("package com.test.app"))
    }

    @Test
    fun `sin android jar lanza Aapt2UnavailableException`() = runTest {
        val resDir = File(tmp, "src/main/res").apply { mkdirs() }
        File(resDir, "values").mkdirs()
        File(resDir, "values/strings.xml").writeText("<resources/>")
        val manifest = File(tmp, "AndroidManifest.xml").apply { writeText("<manifest package=\"com.test\"/>") }
        val fakeAapt2 = makeFakeAapt2()

        val config = fakeConfig(
            resDir = resDir,
            manifest = manifest,
            androidJar = File(tmp, "no-existe-android.jar"),
            aapt2Binary = fakeAapt2
        )
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val ex = assertThrows(Aapt2UnavailableException::class.java) {
            kotlinx.coroutines.runBlocking {
                Aapt2Stage().execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))
            }
        }
        assertTrue(ex.diagnostic.message.contains("android.jar"))
    }

    @Test
    fun `integracion real con aapt2 del PATH si existe`() = runTest {
        val aapt2InPath = findAapt2InPath()
        assumeTrue(aapt2InPath != null, "aapt2 no disponible en PATH, saltando test de integracion")

        val resDir = File(tmp, "src/main/res").apply { mkdirs() }
        File(resDir, "values").mkdirs()
        File(resDir, "values/strings.xml").writeText(
            """<resources><string name="app_name">Test</string></resources>"""
        )
        val manifest = File(tmp, "AndroidManifest.xml").apply {
            writeText("<manifest package=\"com.test.app\"/>")
        }
        // En Termux, el android.jar puede no estar. Saltamos si no existe.
        val androidJar = findAndroidJar()
        assumeTrue(androidJar != null, "android.jar no disponible, saltando test de integracion")

        val config = fakeConfig(
            resDir = resDir,
            manifest = manifest,
            androidJar = androidJar!!,
            aapt2Binary = aapt2InPath
        )
        val diagnostics = mutableListOf<BuildDiagnostic>()

        val result = Aapt2Stage().execute(ResolvedClasspath(emptyList()), fakeCtx(config, diagnostics))

        assertNotNull(result.compiledResourcesApk)
        assertTrue(result.compiledResourcesApk!!.exists())
    }

    private fun findAapt2InPath(): File? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .map { File(it, "aapt2") }
            .firstOrNull { it.exists() && it.canExecute() }
    }

    private fun findAndroidJar(): File? {
        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: return null
        val platforms = File(androidHome, "platforms")
        if (!platforms.isDirectory) return null
        return platforms.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { File(it, "android.jar") }
            ?.firstOrNull { it.exists() }
    }
}
