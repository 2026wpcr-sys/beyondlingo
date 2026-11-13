package dev.beyonddev.asrprobe

import android.content.res.AssetManager
import java.io.File

/**
 * 最小 WAV 读取 + 线性重采样。
 *
 * TTS 合成音通常是 22050/24000Hz，Whisper 需要 16kHz，必须转换。
 * 线性插值对可行性验证足够（正式实现应换带抗混叠的重采样）。
 */
object WavReader {

    fun readAsset(assets: AssetManager, name: String, targetRate: Int): FloatArray {
        val bytes = assets.open(name).use { it.readBytes() }
        return decode(bytes, targetRate)
    }

    fun read(file: File, targetRate: Int): FloatArray = decode(file.readBytes(), targetRate)

    private fun decode(bytes: ByteArray, targetRate: Int): FloatArray {
        if (bytes.size <= 44) return FloatArray(0)

        var pos = 12
        var dataStart = -1
        var dataLen = 0
        var sampleRate = 16000
        var channels = 1
        var bitsPerSample = 16

        // 遍历 chunk：fmt 拿采样率/声道，data 拿数据
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)

            when (id) {
                "fmt " -> {
                    val p = pos + 8
                    if (p + 16 <= bytes.size) {
                        channels = (bytes[p + 2].toInt() and 0xFF) or
                            ((bytes[p + 3].toInt() and 0xFF) shl 8)
                        sampleRate = (bytes[p + 4].toInt() and 0xFF) or
                            ((bytes[p + 5].toInt() and 0xFF) shl 8) or
                            ((bytes[p + 6].toInt() and 0xFF) shl 16) or
                            ((bytes[p + 7].toInt() and 0xFF) shl 24)
                        bitsPerSample = (bytes[p + 14].toInt() and 0xFF) or
                            ((bytes[p + 15].toInt() and 0xFF) shl 8)
                    }
                }
                "data" -> {
                    dataStart = pos + 8
                    dataLen = minOf(size, bytes.size - dataStart)
                    break
                }
            }
            pos += 8 + size + (size and 1)
        }
        if (dataStart < 0 || bitsPerSample != 16) return FloatArray(0)

        val totalSamples = dataLen / 2
        val frames = totalSamples / channels.coerceAtLeast(1)
        if (frames <= 0) return FloatArray(0)

        // 多声道取第一声道（TTS 输出通常单声道，保险起见还是处理）
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            val idx = dataStart + i * channels * 2
            if (idx + 1 >= bytes.size) break
            val lo = bytes[idx].toInt() and 0xFF
            val hi = bytes[idx + 1].toInt()
            mono[i] = ((hi shl 8) or lo) / 32768.0f
        }

        return if (sampleRate == targetRate) mono else resample(mono, sampleRate, targetRate)
    }

    /** 线性插值重采样 */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = from.toDouble() / to
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }
}
