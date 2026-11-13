package dev.beyonddev.beyondlingo.data.asr

import android.content.res.AssetManager

/** 最小 RIFF/WAVE 解析：定位 data chunk，PCM16 转 float。供测试回放使用。 */
object WavUtil {

    fun readFromAssets(assets: AssetManager, name: String): FloatArray {
        val bytes = assets.open(name).use { it.readBytes() }
        return decode(bytes)
    }

    fun readFromFile(file: java.io.File): FloatArray = decode(file.readBytes())

    private fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size > 44) { "wav too small" }

        var pos = 12 // 跳过 RIFF....WAVE
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                dataStart = pos + 8
                dataLen = minOf(size, bytes.size - dataStart)
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataStart > 0) { "no data chunk in wav" }

        val n = dataLen / 2
        val out = FloatArray(n)
        for (k in 0 until n) {
            val lo = bytes[dataStart + k * 2].toInt() and 0xFF
            val hi = bytes[dataStart + k * 2 + 1].toInt()
            out[k] = ((hi shl 8) or lo) / 32768.0f
        }
        return out
    }
}
