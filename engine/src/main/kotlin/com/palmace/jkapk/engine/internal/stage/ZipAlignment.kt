package com.palmace.jkapk.engine.internal.stage

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Helper para escribir entradas STORED (sin comprimir) en un ZipOutputStream,
// alineadas a un multiplo de bytes (4 para resources.arsc, 16384 para .so).
//
// Android 11+ rechaza APKs con resources.arsc comprimido.
// Android 15+ requiere .so alineados a 16384 bytes.
internal object ZipAlignment {

    private val ZIP_LOCAL_HEADER_MAGIC = 0x04034b50.toLong()
    private const val LOCAL_HEADER_SIZE = 30L

    // Escribe una entrada STORED alineada.
    // Debe llamarse entre putNextEntry y closeEntry del mismo ZipOutputStream.
    fun writeStoredAligned(
        zip: ZipOutputStream,
        entryName: String,
        data: ByteArray,
        alignment: Int
    ) {
        val crc = CRC32().apply { update(data) }
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    // Para alignment > 0, hay que insertar padding antes de los datos.
    // ZipOutputStream no permite padding directo; solucion: escribir un entry
    // "extra" con bytes de relleno antes del entry real. Pero eso cambia el
    // contenido del APK (entry extra visible). La solucion correcta es usar
    // un ZIP writer de bajo nivel (como hace apksig).
    //
    // En Fase 7a, sin apksig todavia, usamos una aproximacion: STORED sin
    // padding. La alineacion exacta se delega a apksig en Fase 7b, que
    // reescribe el ZIP respetando alignment. resources.arsc STORED ya
    // cumple el requisito de Android 11+ (uncompressed). El alignment a 4
    // bytes lo hace apksig al firmar.
    fun writeStored(zip: ZipOutputStream, entryName: String, data: ByteArray) {
        writeStoredAligned(zip, entryName, data, alignment = 0)
    }
}
