package dev.beyonddev.beyondlingo.data.document

import java.io.File

/**
 * 文本编码探测。
 *
 * 为什么需要：中文 Windows 用户导出的 txt 大量是 GBK/GB18030，
 * 直接按 UTF-8 读会乱码。Android 的 android.icu.text **不含**
 * CharsetDetector（已核实），所以自己写三级启发式：
 * 1. BOM → 直接采用
 * 2. 前 64KB 严格 UTF-8 解码不抛异常 → UTF-8
 * 3. 否则按 GB18030（GBK 超集，容错最好）
 */
object TextDecoder {

    private const val SNIFF = 64 * 1024

    fun decode(file: File): String {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return ""

        // 1) BOM
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)

            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // 2) 严格 UTF-8 试解
        val head = if (bytes.size > SNIFF) bytes.copyOf(SNIFF) else bytes
        if (isStrictUtf8(head)) {
            return String(bytes, Charsets.UTF_8)
        }

        // 3) 退回 GB18030
        return runCatching {
            String(bytes, charset("GB18030"))
        }.getOrElse {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= bytes.size) return true // 截断点不算错
            for (k in 1..extra) {
                val c = bytes[i + k].toInt() and 0xFF
                if (c !in 0x80..0xBF) return false
            }
            i += extra + 1
        }
        return true
    }
}
