package dev.beyonddev.beyondlingo.data.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAV 编码/解码往返测试。
 *
 * 云端 ASR 的识别质量问题排查中，首要怀疑 WavUtil.encode16kMono 生成的
 * WAV 头是否合法——若头损坏，Groq 可能解码出静音或垃圾。
 */
class WavUtilTest {

    @Test
    fun `正弦波往返解码一致`() {
        val n = 16000 // 1 秒
        val samples = FloatArray(n) { i ->
            (Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 0.5).toFloat()
        }
        val wav = WavUtil.encode16kMono(samples, 16000)
        val back = decodeForTest(wav)
        assertEquals(n, back.size)
        // 16bit 量化误差应很小
        var maxErr = 0f
        for (i in 0 until n) maxErr = maxOf(maxErr, kotlin.math.abs(samples[i] - back[i]))
        assertTrue("量化误差过大: $maxErr", maxErr < 0.001f)
    }

    @Test
    fun `WAV 头字段正确`() {
        val wav = WavUtil.encode16kMono(FloatArray(1600), 16000)
        fun ascii(off: Int, len: Int) = String(wav, off, len, Charsets.US_ASCII)
        fun le16(off: Int) = (wav[off].toInt() and 0xFF) or ((wav[off + 1].toInt() and 0xFF) shl 8)
        fun le32(off: Int) =
            (wav[off].toInt() and 0xFF) or
                ((wav[off + 1].toInt() and 0xFF) shl 8) or
                ((wav[off + 2].toInt() and 0xFF) shl 16) or
                ((wav[off + 3].toInt() and 0xFF) shl 24)

        assertEquals("RIFF", ascii(0, 4))
        assertEquals("WAVE", ascii(8, 4))
        assertEquals("fmt ", ascii(12, 4))
        assertEquals("data", ascii(36, 4))
        assertEquals(1, le16(20))           // PCM
        assertEquals(1, le16(22))           // mono
        assertEquals(16000, le32(24))       // sample rate
        assertEquals(16, le16(34))          // bits
        assertEquals(3200, le32(40))        // data length = 1600 samples * 2
        assertEquals(3200 + 36, le32(4))    // chunk size = 36 + data
    }

    @Test
    fun `静音输入编码出合法的静音 WAV`() {
        // 全是 0 也能出合法文件（排查「采集到静音」时排除编码器自身问题）
        val wav = WavUtil.encode16kMono(FloatArray(800), 16000)
        assertEquals(800 * 2 + 44, wav.size)
        // data 区全是 0
        for (i in 44 until wav.size) assertEquals(0, wav[i].toInt())
    }

    private fun decodeForTest(bytes: ByteArray): FloatArray {
        var pos = 12
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
