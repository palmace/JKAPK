package com.palmace.jkapk.app.domain

import android.content.Context
import android.os.Environment
import java.io.File

data class ToolchainPaths(
    val nativeLibDir: File,
    val sdkRootDir: File,
    val defaultAndroidJar: File,
    val buildCacheDir: File,
    val projectsRootDir: File,
    val appFilesDir: File = File(sdkRootDir.parentFile ?: sdkRootDir, ".."),
    val appCodeCacheDir: File = File(buildCacheDir.parentFile ?: buildCacheDir, "..")
) {
    fun resolveAapt2Binary(): File? {
        val candidates = listOfNotNull(
            File(nativeLibDir, "libaapt2.so"),
            File(sdkRootDir, "build-tools/35.0.0/aapt2"),
            File(sdkRootDir, "build-tools/34.0.0/aapt2"),
            File("/data/data/com.termux/files/usr/bin/aapt2"),
            File("/data/data/com.termux/files/home/Android/Sdk/build-tools/35.0.0/aapt2"),
            File("/data/data/com.termux/files/home/Android/Sdk/build-tools/34.0.0/aapt2")
        )
        return candidates.firstOrNull { it.exists() }
    }

    fun resolveAndroidJar(compileSdk: Int): File {
        val candidates = listOf(
            File(sdkRootDir, "platforms/android-$compileSdk/android.jar"),
            File(sdkRootDir, "platforms/android-34/android.jar"),
            File(sdkRootDir, "platforms/android-35/android.jar"),
            File("/sdcard/download/android-34.jar"),
            File("/sdcard/Download/android-34.jar"),
            defaultAndroidJar
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0L } ?: defaultAndroidJar
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
            val filesDir = appContext.filesDir
            val codeCacheDir = appContext.codeCacheDir

            return ToolchainPaths(
                nativeLibDir = nativeDir,
                sdkRootDir = sdkDir,
                defaultAndroidJar = fallbackJar,
                buildCacheDir = cacheDir,
                projectsRootDir = projectsDir,
                appFilesDir = filesDir,
                appCodeCacheDir = codeCacheDir
            ).apply {
                ensureDirectories()
            }
        }
    }
}
