package dev.beyonddev.beyondlingo.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge TTS 测试：token / 切块 / SSML / 帧解析 / provider。
 *
 * Token 向量由 rany2/edge-tts 7.3.1 的 Python 版 drm.py 生成
 * （generate_sec_ms_gec_v2），Kotlin 移植必须与之一字不差——
 * 错一个字节就是 403。
 */
class EdgeTtsTest {

    // ---- Sec-MS-GEC v2 token ----

    @Test
    fun `token 与 Python 版 drm-py 逐字节一致`() {
        val vectors = mapOf(
            1700000000.0 to
                "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            1794420000.0 to
                "62FCC81D6E138269-21CB8761D8C9D46A4BC80CA43D8F3F1F3EA88251D2616B445F5DEC3E619AAA01",
            1763000000.5 to
                "3689BC1F78238B9A-DD2A2B71647FAA9B316154E46E5EEA92B85708C5C753EC77984ED8A78A848763",
            1000000000.0 to
                "5E9153719FADA922-14D2A75113EC5862E17303287DCDB197E3A671DF9FCC53C100D6B27928384B98",
            0.0 to
                "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            1794423599.9 to
                "19AB41EAF275369C-4CE6A0B48944D2BFBFA275AAE13BE8D17AA87FD224808BCA803CC234F94D302F",
        )
        for ((ts, expected) in vectors) {
            assertEquals("token mismatch at $ts", expected, EdgeTtsToken.generate(ts))
        }
    }

    @Test
    fun `token 格式为 16 位 hex 横杠 64 位 hex 且全大写`() {
        val token = EdgeTtsToken.generate(1794420000.0)
        assertTrue(token, token.matches(Regex("[0-9A-F]{16}-[0-9A-F]{64}")))
        assertEquals("2-143.0.3650.75", EdgeTtsToken.SEC_MS_GEC_VERSION)
    }

    // ---- 音色与语速 ----

    @Test
    fun `按语言选默认音色`() {
        assertEquals("zh-CN-XiaoxiaoNeural", EdgeTtsCodec.voiceFor("zh"))
        assertEquals("es-ES-ElviraNeural", EdgeTtsCodec.voiceFor("es"))
        assertEquals("en-US-AriaNeural", EdgeTtsCodec.voiceFor("en"))
        assertEquals("en-US-AriaNeural", EdgeTtsCodec.voiceFor("xx"))
    }

    @Test
    fun `语速映射到 SSML rate`() {
        assertEquals("+0%", EdgeTtsCodec.rateFor(1.0f))
        assertEquals("-50%", EdgeTtsCodec.rateFor(0.5f))
        assertEquals("+50%", EdgeTtsCodec.rateFor(1.5f))
        assertEquals("+20%", EdgeTtsCodec.rateFor(1.2f))
    }

    // ---- 切块 ----

    @Test
    fun `短文本不切块`() {
        val chunks = EdgeTtsCodec.prepareChunks("你好，这是测试。")
        assertEquals(1, chunks.size)
        assertEquals("你好，这是测试。", chunks[0])
    }

    @Test
    fun `长中文文本按 4096 字节切且不拆多字节字符`() {
        // 每句约 60 字节中文，拼 200 句（约 12000 字节）
        val sentence = "今天天气很好，我们出去走走，顺便买点菜回家做饭。"
        val text = (sentence + " ").repeat(200)
        val chunks = EdgeTtsCodec.prepareChunks(text)
        assertTrue("应切成多块", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("单块超限: ${chunk.toByteArray(Charsets.UTF_8).size}",
                chunk.toByteArray(Charsets.UTF_8).size <= 4096)
            // 每块都必须是合法 UTF-8（String 本身已保证；这里验不丢字）
            assertTrue(chunk.isNotBlank())
        }
        // 拼接后与原文一致（只差切块处的空白）
        val rejoined = chunks.joinToString(" ")
        assertEquals(text.trim().replace(Regex("\\s+"), " "),
            rejoined.replace(Regex("\\s+"), " "))
    }

    @Test
    fun `无空格的长中文文本也能按 4096 字节切开且不超限`() {
        // 中文常无空格：上一版 safeUtf8Split 从整段开头找，会返回整段长度导致超限
        val text = "今天天气很好我们出去走走顺便买点菜回家做饭".repeat(300)
        val chunks = EdgeTtsCodec.prepareChunks(text)
        assertTrue("应切成多块", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("单块超限: ${chunk.toByteArray(Charsets.UTF_8).size}",
                chunk.toByteArray(Charsets.UTF_8).size <= 4096)
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `XML 实体不被拦腰切断`() {
        // 在 4096 字节边界附近放一个 &amp; 实体
        val pad = "啊".repeat(1360) // 4080 字节
        val text = pad + "A &amp; B" + "啊".repeat(100)
        val chunks = EdgeTtsCodec.prepareChunks(text)
        for (chunk in chunks) {
            val amp = chunk.indexOf('&')
            if (amp >= 0) {
                assertTrue("实体被切断: $chunk", chunk.indexOf(';', amp) >= 0)
            }
        }
    }

    @Test
    fun `控制字符被清理`() {
        val cleaned = EdgeTtsCodec.cleanControlChars("a\u000Bb\u0007c")
        assertEquals("a b c", cleaned)
        assertEquals("a\tb\nc", EdgeTtsCodec.cleanControlChars("a\tb\nc"))
    }

    // ---- SSML ----

    @Test
    fun `SSML 含服务端长音色名与转义文本`() {
        val ssml = EdgeTtsCodec.buildSsml(
            voice = "zh-CN-XiaoxiaoNeural",
            escapedText = EdgeTtsCodec.xmlEscape("你好 <世界> & 大家"),
        )
        assertTrue(ssml.contains(
            "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)"))
        assertTrue(ssml.contains("你好 &lt;世界&gt; &amp; 大家"))
        assertTrue(ssml.contains("rate='+0%'"))
    }

    // ---- 帧解析（按 2026-11-13 真实抓包的布局） ----

    private fun realLayoutFrame(): ByteArray {
        val header = "X-RequestId:287c79b5b8244e799f030b751dec913f\r\n" +
            "Content-Type:audio/mpeg\r\n" +
            "X-StreamId:FD42B9D17DF748CBB23FB0C5FC124150\r\n" +
            "Path:audio\r\n"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val mp3 = ByteArray(720) { (it % 256).toByte() }.also {
            it[0] = 0xFF.toByte()
            it[1] = 0xF3.toByte()
        }
        val out = ByteArray(2 + headerBytes.size + mp3.size)
        out[0] = ((headerBytes.size shr 8) and 0xFF).toByte()
        out[1] = (headerBytes.size and 0xFF).toByte()
        headerBytes.copyInto(out, 2)
        mp3.copyInto(out, 2 + headerBytes.size)
        return out
    }

    @Test
    fun `二进制帧按 2 字节头长解析出 MP3`() {
        val (headers, payload) = EdgeTtsCodec.parseBinaryFrame(realLayoutFrame())
        assertEquals("audio", headers["Path"])
        assertEquals("audio/mpeg", headers["Content-Type"])
        assertEquals(720, payload.size)
        assertEquals(0xFF.toByte(), payload[0])
        assertEquals(0xF3.toByte(), payload[1])
    }

    @Test
    fun `二进制空音频帧不报错`() {
        val header = "X-RequestId:x\r\nPath:audio\r\n"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(2 + headerBytes.size)
        frame[0] = ((headerBytes.size shr 8) and 0xFF).toByte()
        frame[1] = (headerBytes.size and 0xFF).toByte()
        headerBytes.copyInto(frame, 2)
        val (headers, payload) = EdgeTtsCodec.parseBinaryFrame(frame)
        assertEquals("audio", headers["Path"])
        assertEquals(0, payload.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `二进制帧 Path 不是 audio 时抛错`() {
        val header = "X-RequestId:x\r\nPath:turn.end\r\n"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(2 + headerBytes.size)
        frame[0] = ((headerBytes.size shr 8) and 0xFF).toByte()
        frame[1] = (headerBytes.size and 0xFF).toByte()
        headerBytes.copyInto(frame, 2)
        EdgeTtsCodec.parseBinaryFrame(frame)
    }

    @Test
    fun `文本帧解析出 turn-end`() {
        val text = "X-RequestId:x\r\nContent-Type:application/json; charset=utf-8\r\n" +
            "Path:turn.end\r\n\r\n{}"
        val (headers, body) = EdgeTtsCodec.parseTextFrame(text)
        assertEquals("turn.end", headers["Path"])
        assertEquals("{}", body)
    }

    // ---- Provider ----

    @Test
    fun `Provider 解析含 edge 且未知值回落本地`() {
        assertEquals(CloudTtsEngine.Provider.EDGE, CloudTtsEngine.Provider.from("edge"))
        assertEquals(CloudTtsEngine.Provider.OPENAI, CloudTtsEngine.Provider.from("openai"))
        assertEquals(CloudTtsEngine.Provider.LOCAL, CloudTtsEngine.Provider.from("bogus"))
        assertEquals(CloudTtsEngine.Provider.LOCAL, CloudTtsEngine.Provider.from(null))
        // EDGE 不走 CloudTtsEngine 合成，usable 恒为 false
        assertEquals(
            false,
            CloudTtsEngine.Config(provider = CloudTtsEngine.Provider.EDGE).usable,
        )
    }
}
