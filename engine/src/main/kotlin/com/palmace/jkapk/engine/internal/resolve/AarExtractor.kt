package com.palmace.jkapk.engine.internal.resolve

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

// Extrae un .aar a cacheDir/aar/<hash>/.
// Idempotente via marcador .extracted.
internal class AarExtractor {

    fun extract(aar: File, cacheDir: File): ExtractedAar {
        require(aar.exists()) { "AAR no existe: ${aar.absolutePath}" }
        require(aar.extension == "aar") { "No es un .aar: ${aar.absolutePath}" }

        val hash = sha256(aar.absolutePath)
        val targetDir = File(cacheDir, "aar/$hash")
        val marker = File(targetDir, ".extracted")

        if (marker.exists()) {
            return buildResult(targetDir)
        }

        targetDir.mkdirs()

        ZipFile(aar).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name

                val outFile: File? = when {
                    name == "classes.jar" -> File(targetDir, "classes.jar")
                    name.startsWith("libs/") && name.endsWith(".jar") ->
                        File(targetDir, name)
                    name == "AndroidManifest.xml" -> File(targetDir, "AndroidManifest.xml")
                    name.startsWith("res/") -> File(targetDir, name)
                    else -> null
                }

                if (outFile != null) {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }

        marker.writeText("ok")
        return buildResult(targetDir)
    }

    private fun buildResult(targetDir: File): ExtractedAar {
        val classesJar = File(targetDir, "classes.jar").takeIf { it.exists() }
        val innerJars = File(targetDir, "libs").takeIf { it.isDirectory }
            ?.listFiles { f -> f.extension == "jar" }
            ?.toList()
            ?: emptyList()
        val resDir = File(targetDir, "res").takeIf { it.isDirectory }
        val manifest = File(targetDir, "AndroidManifest.xml").takeIf { it.exists() }

        return ExtractedAar(
            classesJar = classesJar,
            innerJars = innerJars,
            resDir = resDir,
            manifest = manifest
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }
}

data class ExtractedAar(
    val classesJar: File?,
    val innerJars: List<File>,
    val resDir: File?,
    val manifest: File?
)
