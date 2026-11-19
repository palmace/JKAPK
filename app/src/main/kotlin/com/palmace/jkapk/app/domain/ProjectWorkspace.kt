package com.palmace.jkapk.app.domain

import java.io.File

enum class ProjectLanguage(val displayName: String) {
    KOTLIN("Kotlin"),
    JAVA("Java")
}

data class ProjectWorkspace(
    val name: String,
    val rootDir: File,
    val packageName: String,
    val language: ProjectLanguage = ProjectLanguage.KOTLIN,
    val minSdk: Int = 24,
    val compileSdk: Int = 34,
    val javaVersion: Int = 17
) {
    val configFile: File get() = File(rootDir, "jkapk.json")
    val mainDir: File get() = File(rootDir, "src/main")
    val kotlinDir: File get() = File(mainDir, "kotlin")
    val javaDir: File get() = File(mainDir, "java")
    val resDir: File get() = File(mainDir, "res")
    val assetsDir: File get() = File(mainDir, "assets")
    val libsDir: File get() = File(rootDir, "libs")
    val manifestFile: File get() = File(mainDir, "AndroidManifest.xml")

    fun saveMetadata() {
        rootDir.mkdirs()
        val json = buildString {
            appendLine("{")
            appendLine("  \"name\": \"${escapeJson(name)}\",")
            appendLine("  \"packageName\": \"${escapeJson(packageName)}\",")
            appendLine("  \"language\": \"${language.name}\",")
            appendLine("  \"minSdk\": $minSdk,")
            appendLine("  \"compileSdk\": $compileSdk,")
            appendLine("  \"javaVersion\": $javaVersion")
            appendLine("}")
        }
        configFile.writeText(json)
    }

    companion object {
        private fun escapeJson(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        fun loadFromDir(rootDir: File): ProjectWorkspace? {
            if (!rootDir.exists() || !rootDir.isDirectory) return null
            val metaFile = File(rootDir, "jkapk.json")
            if (!metaFile.exists()) return null

            val raw = metaFile.readText()
            fun extractString(key: String, default: String): String =
                Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1) ?: default
            fun extractInt(key: String, default: Int): Int =
                Regex("\"$key\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: default

            val language = runCatching {
                ProjectLanguage.valueOf(extractString("language", "KOTLIN").uppercase())
            }.getOrDefault(ProjectLanguage.KOTLIN)

            return ProjectWorkspace(
                name = extractString("name", rootDir.name),
                rootDir = rootDir,
                packageName = extractString("packageName", "com.example.app"),
                language = language,
                minSdk = extractInt("minSdk", 24),
                compileSdk = extractInt("compileSdk", 34),
                javaVersion = extractInt("javaVersion", 17)
            )
        }
    }
}
