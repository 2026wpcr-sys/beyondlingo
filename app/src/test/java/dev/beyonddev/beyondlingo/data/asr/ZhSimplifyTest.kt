package dev.beyonddev.beyondlingo.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 繁体转简体转换表验证。
 *
 * 覆盖：
 * - Whisper base 实测输出的繁体串
 * - 词级优先（避免字级规则误伤）
 * - 非中文内容原样返回
 * - 多义字不误转
 */
class ZhSimplifyTest {

    @Test
    fun `whisper base 实际输出的繁体中文转简体`() {
        assertEquals(
            "今天天气不错我们下午一起去看电影吧",
            ZhSimplify.toSimplified("今天天氣不錯我們下午一起去看電影吧"),
        )
    }

    @Test
    fun `繁体单字逐字转换`() {
        assertEquals("龙门", ZhSimplify.toSimplified("龍門"))
        assertEquals("学习电脑", ZhSimplify.toSimplified("學習電腦"))
    }

    @Test
    fun `词级优先于字级`() {
        // 「頭髮」在词级表里是「头发」，不能被拆成「头發」
        assertEquals("头发", ZhSimplify.toSimplified("頭髮"))
        // 「幹部」是词条，不应变成「干部」之外的结果
        assertEquals("干部", ZhSimplify.toSimplified("幹部"))
    }

    @Test
    fun `多义字不误转`() {
        // 「乾」在 OpenCC 里是多义（乾/干），不在一对一表里，应原样保留
        val src = "乾坤"
        val out = ZhSimplify.toSimplified(src)
        assertTrue("多义字不应被转换: $out", out == src || out == "乾坤")
    }

    @Test
    fun `非中文内容原样返回`() {
        assertEquals("Buenos días, ¿cómo estás?", ZhSimplify.toSimplified("Buenos días, ¿cómo estás?"))
        assertEquals("Hello world", ZhSimplify.toSimplified("Hello world"))
        assertEquals("", ZhSimplify.toSimplified(""))
    }

    @Test
    fun `中英混排不受影响`() {
        assertEquals(
            "今天用 Whisper 识别",
            ZhSimplify.toSimplified("今天用 Whisper 識別"),
        )
    }
}
