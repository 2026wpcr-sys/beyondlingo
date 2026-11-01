package dev.beyonddev.beyondlingo.util

import dev.beyonddev.beyondlingo.data.engine.LangEnum

/**
 * 双向翻译的方向解析。
 *
 * 使用场景：用户只声明「我的语言」和「对方语言」，不再手动切换方向。
 * 输入是母语→译成对方语言；输入是对方语言→译成母语。
 *
 * 为什么不用通用语言检测：
 * 本应用的双向场景只有两种可能（母语 / 对方语言），做二分类比
 * 33 种语言的通用检测更准。中文判定用 Unicode 汉字占比，
 * 足以区分「中文」与「任何拉丁字母语言」。
 *
 * @see LanguageDetector 通用检测器，仍保留供将来的多语言场景使用
 */
object DirectionResolver {

    /** 判定为「母语」所需的汉字占比阈值。 */
    private const val HAN_RATIO_THRESHOLD = 0.3

    /**
     * 解析一次翻译的源语言与目标语言。
     *
     * @param text 待翻译文本
     * @param homeLang 用户母语（例如简体中文）
     * @param foreignLang 对方语言（例如西班牙语）
     * @return 有序对 (源语言, 目标语言)
     */
    fun resolve(
        text: String,
        homeLang: LangEnum,
        foreignLang: LangEnum,
    ): Pair<LangEnum, LangEnum> {
        // 母语与对方语言相同时无方向可言，直接原样返回
        if (homeLang == foreignLang) return homeLang to foreignLang

        // 母语是中文时，用汉字占比判定；母语不是中文时，用汉字占比反推
        val looksLikeHome = if (isChinese(homeLang)) {
            hasEnoughHan(text)
        } else if (isChinese(foreignLang)) {
            // 对方语言是中文：没有汉字就说明是母语
            !hasEnoughHan(text)
        } else {
            // 双方都不是中文（例如英语 ⇄ 西班牙语）：无法用汉字区分，
            // 退回通用检测；检测不出则假定为对方语言（外译母语更常见）
            val detected = LanguageDetector.detect(text)
            detected == homeLang
        }

        return if (looksLikeHome) {
            homeLang to foreignLang
        } else {
            foreignLang to homeLang
        }
    }

    /**
     * 判断文本是否以某种语言为主（用于 UI 显示「检测到 XX」）。
     *
     * @return 命中的语言；无法判断时返回 null
     */
    fun detectAmong(
        text: String,
        homeLang: LangEnum,
        foreignLang: LangEnum,
    ): LangEnum? {
        if (text.isBlank()) return null
        if (homeLang == foreignLang) return homeLang
        return resolve(text, homeLang, foreignLang).first
    }

    private fun isChinese(lang: LangEnum): Boolean =
        lang == LangEnum.ChineseSimplified ||
            lang == LangEnum.ChineseTraditional ||
            lang == LangEnum.Cantonese

    /** 汉字占「有效字符」的比例是否达到阈值。 */
    private fun hasEnoughHan(text: String): Boolean {
        var han = 0
        var total = 0
        for (ch in text) {
            when {
                ch.isWhitespace() || ch.isDigit() -> {}
                ch in '\u4E00'..'\u9FFF' -> { han++; total++ }
                // 标点与符号不计入分母，否则「Hello?」这类短句会被稀释
                ch.isLetterOrDigit() -> total++
                else -> {}
            }
        }
        if (total == 0) return false
        return han.toDouble() / total >= HAN_RATIO_THRESHOLD
    }
}
