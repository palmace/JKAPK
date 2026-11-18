package com.palmace.jkapk.engine.internal.resolve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AarExtractorTest {

    @TempDir
    lateinit var tmp: File

    private fun makeSyntheticAar(): File {
        val aar = File(tmp, "test.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            // classes.jar sintético (contiene solo un fichero dummy)
            val innerJarBytes = buildInnerJarBytes()
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(innerJarBytes)
            zip.closeEntry()

            // AndroidManifest.xml
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package=\"com.test\"/>".toByteArray())
            zip.closeEntry()

            // res/values/strings.xml
            zip.putNextEntry(ZipEntry("res/values/strings.xml"))
            zip.write("<resources/>".toByteArray())
            zip.closeEntry()
        }
        return aar
    }

    private fun buildInnerJarBytes(): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            zip.putNextEntry(ZipEntry("Dummy.class"))
            zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zip.closeEntry()
        }
        return buf.toByteArray()
    }

    @Test
    fun `extrae classes jar manifest y res`() {
        val aar = makeSyntheticAar()
        val cache = File(tmp, "cache").apply { mkdirs() }
        val extractor = AarExtractor()

        val result = extractor.extract(aar, cache)

        assertNotNull(result.classesJar, "classes.jar debe existir")
        assertTrue(result.classesJar!!.exists())
        assertNotNull(result.manifest)
        assertNotNull(result.resDir)
        assertTrue(result.resDir!!.isDirectory)
    }

    @Test
    fun `extraccion es idempotente via marcador`() {
        val aar = makeSyntheticAar()
        val cache = File(tmp, "cache").apply { mkdirs() }
        val extractor = AarExtractor()

        val first = extractor.extract(aar, cache)
        val second = extractor.extract(aar, cache)

        assertEquals(first.classesJar, second.classesJar)
        assertEquals(first.manifest, second.manifest)
    }
}
