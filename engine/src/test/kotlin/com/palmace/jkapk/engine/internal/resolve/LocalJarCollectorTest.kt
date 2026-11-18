package com.palmace.jkapk.engine.internal.resolve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalJarCollectorTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `encuentra jar y aar recursivamente`() {
        val libs = File(tmp, "libs").apply { mkdirs() }
        File(libs, "a.jar").writeText("dummy")
        File(libs, "b.aar").writeText("dummy")
        val nested = File(libs, "nested").apply { mkdirs() }
        File(nested, "c.jar").writeText("dummy")

        val result = LocalJarCollector().collect(listOf(libs))

        assertEquals(2, result.jars.size)
        assertEquals(1, result.aars.size)
    }

    @Test
    fun `ignora directorios inexistentes`() {
        val result = LocalJarCollector().collect(listOf(File(tmp, "no-existe")))
        assertEquals(0, result.jars.size)
        assertEquals(0, result.aars.size)
    }

    @Test
    fun `directorios multiples sin duplicados`() {
        val libs1 = File(tmp, "libs1").apply { mkdirs() }
        val libs2 = File(tmp, "libs2").apply { mkdirs() }
        File(libs1, "shared.jar").writeText("dummy")
        File(libs2, "shared.jar").writeText("dummy")

        val result = LocalJarCollector().collect(listOf(libs1, libs2))
        assertEquals(2, result.jars.size) // mismos nombres, distintos paths → distintos jars
    }
}
