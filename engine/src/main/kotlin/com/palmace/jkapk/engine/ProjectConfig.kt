package com.palmace.jkapk.engine

import java.io.File

enum class JavaVersion(val major: Int) {
    VERSION_8(8),
    VERSION_11(11),
    VERSION_17(17),
    VERSION_21(21);

    val flag: String get() = "--release=$major"
}

data class ProjectConfig(
    val projectName: String,
    val projectRoot: File,
    val javaSourceDirs: List<File>,
    val kotlinSourceDirs: List<File>,
    val resourceDir: File?,
    val assetDir: File?,
    val manifestFile: File,
    val libsDirs: List<File> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
    val packageName: String,
    val compileSdk: Int,
    val minSdk: Int,
    val targetSdk: Int = compileSdk,
    val javaVersion: JavaVersion = JavaVersion.VERSION_17,
    val multidex: Boolean = true,
    val incremental: Boolean = true,
    val buildOutputDir: File,
    val androidJar: File = File(""),
    val aapt2Binary: File? = null
)
