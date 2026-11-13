package dev.beyonddev.beyondlingo.data.asr

/**
 * Edge TTS 的纯协议逻辑：文本清理 / XML 转义 / 4096 字节切块 / SSML 生成 /
 * WebSocket 帧解析 / 按语言选音色。
 *
 * 与 rany2/edge-tts 7.3.1 `communicate.py` 对齐（`split_text_by_byte_length`、
 * `mkssml`、`get_headers_and_data`），并以 2026-11-13 的真实抓包修正了
 * 二进制帧的解析：
 *
 * 二进制帧 = [2 字节大端头长 L][L 字节头块][MP3 数据]，头块以 `\r\n` 结尾，
 * 头长**包含**末尾的 `\r\n`，MP3 紧随其后、中间无多余 CRLF。
 * 头块示例：`X-RequestId:…\r\nContent-Type:audio/mpeg\r\nX-StreamId:…\r\nPath:audio\r\n`
 *
 * 注意：edge-tts 的 Python 版解析是 `data[:L]` / `data[L+2:]`——它能工作
 * 只是因为头块第一行是没人校验的 X-RequestId、Path 在最后一行；
 * 这里按真实布局解析，不依赖这个巧合。
 *
 * 本文件是纯 Kotlin（无 Android 依赖），可在 JVM 单测里直接跑。
 */
internal object EdgeTtsCodec {

    /** 服务端单块 SSML 上限（2025-12 起强制执行，超限会静默无响应） */
    const val MAX_CHUNK_BYTES = 4096

    /** 每块的默认语速/音量/音调（SSML prosody 属性值） */
    const val DEFAULT_RATE = "+0%"
    const val DEFAULT_VOLUME = "+0%"
    const val DEFAULT_PITCH = "+0Hz"

    /** 按 ISO-639-1 语言码选默认音色（中文晓晓 / 西语 Elvira） */
    fun voiceFor(langCode: String): String = when (langCode.lowercase()) {
        "zh" -> "zh-CN-XiaoxiaoNeural"
        "es" -> "es-ES-ElviraNeural"
        "en" -> "en-US-AriaNeural"
        "ja" -> "ja-JP-NanamiNeural"
        "ko" -> "ko-KR-SunHiNeural"
        "fr" -> "fr-FR-DeniseNeural"
        "de" -> "de-DE-KatjaNeural"
        "ru" -> "ru-RU-SvetlanaNeural"
        else -> "en-US-AriaNeural"
    }

    /** 语速倍率（本地 TTS 的 speed 概念，0.5–1.5）→ SSML prosody rate */
    fun rateFor(speed: Float): String {
        val pct = ((speed.coerceIn(0.5f, 1.5f) - 1f) * 100).toInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    /** C0 控制字符（除 \t \n \r）替换为空格 —— 服务端收到 \x0b 之类会直接报错 */
    fun cleanControlChars(s: String): String = buildString(s.length) {
        for (ch in s) {
            val code = ch.code
            append(if ((code in 0..8) || (code in 11..12) || (code in 14..31)) ' ' else ch)
        }
    }

    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 文本预处理：清控制字符 → XML 转义 → 按 4096 字节切块（与 edge-tts 同款顺序） */
    fun prepareChunks(text: String): List<String> =
        splitByByteLength(xmlEscape(cleanControlChars(text)), MAX_CHUNK_BYTES)

    /**
     * 把（已 XML 转义）文本按 ≤limit 字节切块 —— 等价 edge-tts split_text_by_byte_length：
     * 优先在 \n / 空格断；否则退到合法 UTF-8 边界；绝不把 &…; 实体拦腰切断。
     */
    fun splitByByteLength(escaped: String, limit: Int): List<String> {
        require(limit > 0) { "byte_length must be positive" }
        val out = ArrayList<String>()
        var text = escaped.toByteArray(Charsets.UTF_8)
        while (text.size > limit) {
            var splitAt = lastIndexOf(text, '\n'.code.toByte(), 0, limit)
            if (splitAt < 0) splitAt = lastIndexOf(text, ' '.code.toByte(), 0, limit)
            if (splitAt < 0) splitAt = safeUtf8Split(text, limit)
            splitAt = adjustForXmlEntity(text, splitAt)
            require(splitAt >= 0) { "max byte length too small for this text" }
            val chunk = String(text, 0, splitAt, Charsets.UTF_8).trim()
            if (chunk.isNotEmpty()) out.add(chunk)
            text = text.copyOfRange(if (splitAt > 0) splitAt else 1, text.size)
        }
        val rest = String(text, Charsets.UTF_8).trim()
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    private fun lastIndexOf(b: ByteArray, target: Byte, from: Int, until: Int): Int {
        var i = until - 1
        while (i >= from) {
            if (b[i] == target) return i
            i--
        }
        return -1
    }

    /** 从 limit 向前找最长合法 UTF-8 前缀长度（找不到返回 0）。
     * 必须从 limit 开始：从整段开头找会返回整段长度，单块就超限了。 */
    private fun safeUtf8Split(text: ByteArray, limit: Int): Int {
        var splitAt = minOf(limit, text.size)
        while (splitAt > 0) {
            if (isValidUtf8Prefix(text, splitAt)) return splitAt
            splitAt--
        }
        return 0
    }

    private fun isValidUtf8Prefix(b: ByteArray, n: Int): Boolean {
        var i = 0
        while (i < n) {
            val u = b[i].toInt() and 0xFF
            val need = when {
                u < 0x80 -> 1
                u and 0xE0 == 0xC0 -> 2
                u and 0xF0 == 0xE0 -> 3
                u and 0xF8 == 0xF0 -> 4
                else -> return false
            }
            if (i + need > n) return false
            for (j in i + 1 until i + need) {
                if ((b[j].toInt() and 0xC0) != 0x80) return false
            }
            i += need
        }
        return true
    }

    /** 若切点前有未闭合的 &…（到切点无 ;），切点退到该 & 之前 */
    private fun adjustForXmlEntity(text: ByteArray, splitAtIn: Int): Int {
        var splitAt = splitAtIn
        while (splitAt > 0) {
            var amp = -1
            for (i in 0 until splitAt) if (text[i] == '&'.code.toByte()) amp = i
            if (amp < 0) break
            var closed = false
            for (i in amp + 1 until splitAt) {
                if (text[i] == ';'.code.toByte()) { closed = true; break }
            }
            if (closed) break
            splitAt = amp
        }
        return splitAt
    }

    /** voice 名 → 服务端长名；已是长名或无法识别则原样返回 */
    fun voiceFullName(voice: String): String {
        if (voice.startsWith("Microsoft Server Speech Text to Speech Voice")) return voice
        val m = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(voice) ?: return voice
        var region = m.groupValues[2]
        var name = m.groupValues[3]
        val dash = name.indexOf('-')
        if (dash != -1) {
            region = "$region-${name.substring(0, dash)}"
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice (${m.groupValues[1]}-$region, $name)"
    }

    fun buildSsml(
        voice: String,
        escapedText: String,
        rate: String = DEFAULT_RATE,
        volume: String = DEFAULT_VOLUME,
        pitch: String = DEFAULT_PITCH,
    ): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${voiceFullName(voice)}'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
            escapedText +
            "</prosody></voice></speak>"

    /** 文本帧：按首个 \r\n\r\n 切分 →（头字段，body） */
    fun parseTextFrame(data: String): Pair<Map<String, String>, String> {
        val idx = data.indexOf("\r\n\r\n")
        require(idx >= 0) { "text frame missing CRLFCRLF" }
        return parseHeaderLines(data.substring(0, idx)) to data.substring(idx + 4)
    }

    /**
     * 二进制音频帧：[0..2) 头长 L（大端 uint16）；[2..2+L) 头块；[2+L..] MP3。
     * 返回（头字段，MP3 数据）。头块不含 Path:audio 或 Content-Type 非 audio/mpeg 时抛错。
     */
    fun parseBinaryFrame(frame: ByteArray): Pair<Map<String, String>, ByteArray> {
        require(frame.size >= 2) { "binary frame shorter than 2 bytes" }
        val headerLen = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        require(headerLen <= frame.size - 2) { "binary frame header length invalid" }
        var payload = frame.copyOfRange(2 + headerLen, frame.size)
        // 兼容分支：个别历史样例在帧头与音频间多一个 CRLF
        if (payload.size >= 2 && payload[0] == '\r'.code.toByte() &&
            payload[1] == '\n'.code.toByte()
        ) {
            payload = payload.copyOfRange(2, payload.size)
        }
        val headers = parseHeaderLines(
            String(frame, 2, headerLen, Charsets.UTF_8),
        )
        require(headers["Path"] == "audio") { "binary frame path is not audio" }
        val contentType = headers["Content-Type"]
        require(contentType == null || contentType == "audio/mpeg") {
            "unexpected Content-Type: $contentType"
        }
        return headers to payload
    }

    private fun parseHeaderLines(headers: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in headers.split("\r\n")) {
            val c = line.indexOf(':')
            if (c > 0) map[line.substring(0, c)] = line.substring(c + 1)
        }
        return map
    }
}
