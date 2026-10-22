package dev.beyonddev.beyondlingo.util

import dev.beyonddev.beyondlingo.data.engine.LangEnum

/**
 * 本地语言启发式检测。
 *
 * 为什么需要它：
 * 引擎自带的自动检测有时会把短文本判错（尤其中英混排）。
 * 这里用 Unicode 区块占比做一个快速预判，命中率高且零成本。
 *
 * 注意：这只用于「让引擎少犯错」，不保证准确；
 * 最终识别结果仍以引擎返回为准（如果引擎返回了 detectedSource）。
 */
object LanguageDetector {

    /**
     * 检测文本的主语言。
     *
     * @return 检测到的语言；无法判断时返回 [LangEnum.Auto]
     */
    fun detect(text: String): LangEnum {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return LangEnum.Auto

        // 统计各字符类别占比（忽略空格、数字、标点）
        var han = 0          // 汉字
        var kana = 0         // 日文假名
        var hangul = 0       // 韩文
        var cyrillic = 0     // 西里尔字母
        var arabic = 0       // 阿拉伯字母
        var thai = 0         // 泰文
        var latin = 0        // 拉丁字母
        var total = 0

        for (ch in trimmed) {
            when {
                ch.isWhitespace() -> {}
                ch.isDigit() -> {}
                ch in '\u4E00'..'\u9FFF' -> { han++; total++ }
                ch in '\u3040'..'\u30FF' -> { kana++; total++ }
                ch in '\uAC00'..'\uD7AF' -> { hangul++; total++ }
                ch in '\u0400'..'\u04FF' -> { cyrillic++; total++ }
                ch in '\u0600'..'\u06FF' -> { arabic++; total++ }
                ch in '\u0E00'..'\u0E7F' -> { thai++; total++ }
                ch in 'a'..'z' || ch in 'A'..'Z' ||
                    ch in '\u00C0'..'\u024F' -> { latin++; total++ }
                else -> {}
            }
        }

        if (total == 0) return LangEnum.Auto

        val ratio = { n: Int -> n.toDouble() / total }

        // 假名 + 汉字 → 日语（假名是决定性特征）
        if (kana > 0 && ratio(kana) >= 0.05) return LangEnum.Japanese

        // 韩文
        if (ratio(hangul) >= 0.3) return LangEnum.Korean

        // 汉字为主 → 中文（简体/繁体区分需要更复杂规则，这里给简体）
        if (ratio(han) >= 0.5) return LangEnum.ChineseSimplified

        // 西里尔字母 → 俄语
        if (ratio(cyrillic) >= 0.4) return LangEnum.Russian

        // 阿拉伯字母
        if (ratio(arabic) >= 0.4) return LangEnum.Arabic

        // 泰文
        if (ratio(thai) >= 0.4) return LangEnum.Thai

        // 拉丁字母为主 → 英语（最常见的拉丁语言，交给引擎再判细）
        if (ratio(latin) >= 0.5) return LangEnum.English

        return LangEnum.Auto
    }

    /**
     * 判断是否应该跳过翻译（例如源语言与目标语言相同）。
     */
    fun shouldSkip(text: String, source: LangEnum, target: LangEnum): Boolean {
        if (source == LangEnum.Auto) return false
        return source == target && text.isNotBlank()
    }
}
