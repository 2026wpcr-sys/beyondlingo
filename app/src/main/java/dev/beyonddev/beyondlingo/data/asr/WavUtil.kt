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

    /**
     * FloatArray（[-1,1]）→ 16bit 单声道 WAV 字节。
     *
     * 云端 ASR（Groq）要求上传音频文件；Groq 会自己降采样到 16kHz mono，
     * 所以这里直接输出 16k/mono/PCM16，不额外转码，省掉 ffmpeg 依赖。
     */
    fun encode16kMono(samples: FloatArray, sampleRate: Int = 16000): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArray(44 + dataLen)

        fun putInt(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
            out[off + 2] = ((v shr 16) and 0xFF).toByte()
            out[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        // RIFF header
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        putInt(4, 36 + dataLen)              // ChunkSize
        out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte()
        out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
        // fmt chunk
        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        putInt(16, 16)                       // Subchunk1Size
        putShort(20, 1)                      // AudioFormat = PCM
        putShort(22, 1)                      // NumChannels = mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)           // ByteRate
        putShort(32, 2)                      // BlockAlign
        putShort(34, 16)                     // BitsPerSample
        // data chunk
        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        putInt(40, dataLen)

        var off = 44
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            putShort(off, v)
            off += 2
        }
        return out
    }
}
