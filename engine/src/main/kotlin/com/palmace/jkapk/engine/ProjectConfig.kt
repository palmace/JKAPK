package com.palmace.jkapk.engine

import java.io.File

enum class JavaVersion(val release: String) {
    JAVA_11("11"),
    JAVA_17("17"),
    JAVA_21("21")
}

data class ProjectConfig(
    val projectName: String,
    val projectRoot: File,
    val javaSourceDirs: List<File>,
    val kotlinSourceDirs: List<File>,
    val resourceDir: File?,
    val assetDir: File?,
    val libsDirs: List<File> = emptyList(),
    val manifestFile: File,
    val packageName: String,
    val compileSdk: Int,
    val minSdk: Int,
    val buildOutputDir: File,
    val javaVersion: JavaVersion = JavaVersion.JAVA_17,
    val androidJar: File = File(""),
    val aapt2Binary: File? = null,
    val signingConfig: SigningConfig? = null
)
