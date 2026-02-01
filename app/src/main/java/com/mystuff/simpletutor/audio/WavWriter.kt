package com.mystuff.simpletutor.audio

import java.io.File
import java.io.FileOutputStream

object WavWriter {
    fun writePcm16Mono(
        file: File,
        pcmBytes: ByteArray,
        sampleRate: Int = VadConfig.sampleRateHz
    ) {
        val dataSize = pcmBytes.size
        val totalDataLen = dataSize + 36
        val byteRate = sampleRate * 2

        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToLittleEndian(totalDataLen))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToLittleEndian(16))
            out.write(shortToLittleEndian(1))
            out.write(shortToLittleEndian(1))
            out.write(intToLittleEndian(sampleRate))
            out.write(intToLittleEndian(byteRate))
            out.write(shortToLittleEndian(2))
            out.write(shortToLittleEndian(16))
            out.write("data".toByteArray())
            out.write(intToLittleEndian(dataSize))
            out.write(pcmBytes)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )
}
