package com.palmace.jkapk.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.palmace.jkapk.app.build.BuildSessionController
import com.palmace.jkapk.app.data.ProjectConfigMapper
import com.palmace.jkapk.app.data.ProjectScanner
import com.palmace.jkapk.app.data.ProjectTemplateGenerator
import com.palmace.jkapk.app.domain.ToolchainPaths
import com.palmace.jkapk.engine.BuildEngine
import java.io.File

class JkapkApplication : Application() {

    lateinit var toolchainPaths: ToolchainPaths
        private set

    lateinit var configMapper: ProjectConfigMapper
        private set

    lateinit var templateGenerator: ProjectTemplateGenerator
        private set

    lateinit var projectScanner: ProjectScanner
        private set

    lateinit var buildEngine: BuildEngine
        private set

    lateinit var buildSessionController: BuildSessionController
        private set

    val projectsDirectory: File
        get() = toolchainPaths.projectsRootDir.apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        instance = this

        toolchainPaths = ToolchainPaths.fromContext(this)
        configMapper = ProjectConfigMapper(toolchainPaths)
        templateGenerator = ProjectTemplateGenerator()
        projectScanner = ProjectScanner()

        buildEngine = createBuildEngine()
        buildSessionController = BuildSessionController(
            buildEngine = buildEngine,
            configMapper = configMapper
        )
    }

    private fun createBuildEngine(): BuildEngine {
        val engineClass = Class.forName("com.palmace.jkapk.engine.internal.DefaultBuildEngine")
        val ctor = engineClass.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance() as BuildEngine
    }

    fun prepareApkForInstall(sourceApk: File): File {
        require(sourceApk.exists()) { "El archivo APK no existe: ${sourceApk.absolutePath}" }
        projectsDirectory.mkdirs()

        val stagingDir = File(cacheDir, "apk-install").apply {
            if (!exists()) {
                mkdirs()
            }
        }
        val targetApk = File(stagingDir, sourceApk.name)
        targetApk.parentFile?.mkdirs()
        sourceApk.copyTo(targetApk, overwrite = true)
        return targetApk
    }

    fun createInstallApkIntent(context: Context, sourceApk: File): Intent {
        val stagedApk = prepareApkForInstall(sourceApk)
        val apkUri: Uri = Uri.fromFile(stagedApk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        lateinit var instance: JkapkApplication
            private set
    }
}
