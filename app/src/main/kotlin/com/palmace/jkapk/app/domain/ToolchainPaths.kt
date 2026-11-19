package com.palmace.jkapk.app.domain

import android.content.Context
import android.os.Environment
import java.io.File

data class ToolchainPaths(
    val nativeLibDir: File,
    val sdkRootDir: File,
    val defaultAndroidJar: File,
    val buildCacheDir: File,
    val projectsRootDir: File
) {
    fun resolveAapt2Binary(): File? {
        val candidate = File(nativeLibDir, "libaapt2.so")
        return candidate.takeIf { it.exists() && it.canExecute() }
    }

    fun resolveAndroidJar(compileSdk: Int): File {
        val sdkJar = File(sdkRootDir, "platforms/android-$compileSdk/android.jar")
        if (sdkJar.exists()) return sdkJar
        return defaultAndroidJar
    }

    fun ensureDirectories() {
        sdkRootDir.mkdirs()
        defaultAndroidJar.parentFile?.mkdirs()
        buildCacheDir.mkdirs()
        projectsRootDir.mkdirs()
    }

    companion object {
        fun resolveDefaultProjectsDir(context: Context): File {
            val baseExternal = Environment.getExternalStorageDirectory()
            val baseDir = if (baseExternal != null && baseExternal.exists()) {
                baseExternal
            } else {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
            return File(baseDir, "download/Jkapkprojects").apply { mkdirs() }
        }

        fun fromContext(context: Context): ToolchainPaths {
            val appContext = context.applicationContext
            val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
            val sdkDir = File(appContext.filesDir, "android-sdk")
            val fallbackJar = File(sdkDir, "platforms/android-34/android.jar")
            val cacheDir = File(appContext.cacheDir, "jkapk-build-cache")
            val projectsDir = resolveDefaultProjectsDir(appContext)

            return ToolchainPaths(
                nativeLibDir = nativeDir,
                sdkRootDir = sdkDir,
                defaultAndroidJar = fallbackJar,
                buildCacheDir = cacheDir,
                projectsRootDir = projectsDir
            ).apply {
                ensureDirectories()
            }
        }
    }
}
