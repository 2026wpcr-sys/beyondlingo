package dev.beyonddev.beyondlingo.data.asr

/**
 * 繁体转简体。
 *
 * 背景：Whisper base 在中文上会输出繁体（实测
 * 「今天天氣不錯我們下午一起去看電影吧」），而 tiny 输出简体。
 * 两种混在一起会让字幕风格不一致，部分翻译引擎对繁体处理也略差。
 * 翻译鸥同样有这层处理（DEX 实证：`openccApplied=true, businessLanguage=`）。
 *
 * 为什么不引 OpenCC 依赖：Android 侧的 OpenCC 移植要么词典占数 MB，
 * 要么长期无人维护；实际只需要「Whisper 可能输出的繁体」这一小块覆盖面。
 *
 * 转换策略：
 * 1. 先按**词级**表整词替换（最可靠，词表来自 OpenCC TSPhrases）
 * 2. 剩下的字按**字级**表逐字替换（TSCharacters，只收一对一）
 * 3. 多义字（乾/後/髮/發…）不在表中，一律不转，避免误伤
 */
object ZhSimplify {

    private const val MAX_PHRASE_LEN = 6

    /**
     * @param text 任意文本；非中文内容原样返回
     */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        // 快速判断：没有 CJK 就直接返回（西语/英语等不需要转换）
        if (!text.any { isCjk(it) }) return text

        // 1) 词级替换：命中词条的整段直接输出简体，不再参与字级处理
        val sb = StringBuilder(text.length)
        var i = 0
        outer@ while (i < text.length) {
            for (len in minOf(MAX_PHRASE_LEN, text.length - i) downTo 2) {
                val hit = ZhSimplifyTable.PHRASES[text.substring(i, i + len)]
                if (hit != null) {
                    sb.append(hit)
                    i += len
                    continue@outer
                }
            }
            // 未命中词级 → 单字走字级表
            val ch = text[i]
            sb.append(ZhSimplifyTable.CHARS[ch] ?: ch)
            i++
        }
        return sb.toString()
    }

    /** 是否是 CJK 统一表意文字（覆盖到扩展区与兼容区） */
    private fun isCjk(c: Char): Boolean {
        val v = c.code
        return v in 0x4E00..0x9FFF || v in 0x3400..0x4DBF || v in 0xF900..0xFAFF
    }
}
