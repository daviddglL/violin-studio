package com.violinstudio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * MP4 minimo que el parser de `finalizeRecording` acepta: `ftyp` con la marca [brand] (`M4A ` en audio, `isom` en
 * video), `moov` con `mvhd` v0 (escala 1000) y un `mdat` de relleno. El emulador de Android arranca sin audio ni camara
 * real, asi que los E2E de grabacion suben este fichero en lugar de una toma grabada.
 */
fun minimalMp4(brand: String, durationMs: Long): ByteArray {
    require(brand.length == 4) { "la marca de ftyp tiene 4 caracteres" }
    fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + payload.size)
            writeBytes(type)
            write(payload)
        }
        return out.toByteArray()
    }
    val ftyp = ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
            writeBytes(brand)
            writeInt(0)
            writeBytes("isom")
        }
    }.toByteArray()
    val mvhd = ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
            writeInt(0) // version 0 y flags
            writeInt(0) // creation_time
            writeInt(0) // modification_time
            writeInt(1_000) // timescale
            writeInt(durationMs.toInt())
            write(ByteArray(80))
        }
    }.toByteArray()
    return box("ftyp", ftyp) + box("moov", box("mvhd", mvhd)) + box("mdat", ByteArray(16 * 1024))
}
