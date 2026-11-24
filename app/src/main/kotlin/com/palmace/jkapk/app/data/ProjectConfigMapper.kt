package com.palmace.jkapk.app.data

import com.palmace.jkapk.app.domain.ProjectWorkspace
import com.palmace.jkapk.app.domain.ToolchainPaths
import com.palmace.jkapk.engine.JavaVersion
import com.palmace.jkapk.engine.ProjectConfig
import com.palmace.jkapk.engine.SigningConfig
import java.io.File

class ProjectConfigMapper(
    private val toolchainPaths: ToolchainPaths
) {
    fun toEngineConfig(
        appCacheDir: File,
        workspace: ProjectWorkspace,
        signingConfig: SigningConfig? = null,
        androidJarOverride: File? = null
    ): ProjectConfig {
        val javaDirs = listOfNotNull(workspace.javaDir.takeIf { it.isDirectory })
        val kotlinDirs = listOfNotNull(workspace.kotlinDir.takeIf { it.isDirectory })
        val resDir = workspace.resDir.takeIf { it.isDirectory }
        val assetDir = workspace.assetsDir.takeIf { it.isDirectory }
        val libsDirs = listOfNotNull(workspace.libsDir.takeIf { it.isDirectory })
        val outputDir = File(workspace.rootDir, "build/outputs/apk").apply { mkdirs() }

        val mappedJavaVersion = when (workspace.javaVersion) {
            11 -> JavaVersion.JAVA_11
            21 -> JavaVersion.JAVA_21
            else -> JavaVersion.JAVA_17
        }

        // Usar el androidJar inyectado, o resolverlo por defecto
        val resolvedAndroidJar = androidJarOverride 
            ?: toolchainPaths.defaultAndroidJar

        return ProjectConfig(
            projectName = workspace.name,
            projectRoot = workspace.rootDir,
            javaSourceDirs = javaDirs,
            kotlinSourceDirs = kotlinDirs,
            resourceDir = resDir,
            assetDir = assetDir,
            libsDirs = libsDirs,
            manifestFile = workspace.manifestFile,
            packageName = workspace.packageName,
            compileSdk = workspace.compileSdk,
            minSdk = workspace.minSdk,
            buildOutputDir = outputDir,
            appCacheDir = appCacheDir,
            javaVersion = mappedJavaVersion,
            androidJar = resolvedAndroidJar,
            aapt2Binary = toolchainPaths.resolveAapt2Binary(),
            signingConfig = signingConfig,
            internalBinDirs = listOf(
                File(toolchainPaths.appFilesDir, "jkapk-bin"),
                File(toolchainPaths.appCodeCacheDir, "jkapk-bin")
            )
        )
    }
}
