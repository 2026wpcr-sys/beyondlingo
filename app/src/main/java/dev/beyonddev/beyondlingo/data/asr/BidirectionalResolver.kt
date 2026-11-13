package dev.beyonddev.beyondlingo.data.asr

import dev.beyonddev.beyondlingo.data.engine.LangEnum

/**
 * 双向同传的方向判定。
 *
 * 设计参照翻译鸥的 `BidirectionalSpeechEventProcessor#resolveDirection` +
 * `inheritMatchingRecognizingLanguage`（DEX 实证，见
 * docs/transgull-simult-reference.md）：
 *
 * - 两侧各有一个语言槽：left（我方）/ right（对方）
 * - 识别结果自带语言 → 与两个槽比对，决定这句是谁说的、往哪个方向译
 * - 检测不出或两边都不匹配 → **继承上一轮方向**，而不是丢弃
 * - 连续继承也失败 → UNKNOWN，调用方决定跳过
 *
 * 翻译鸥还有「预览投票」抗抖动（voteBidirectionalPreviewTranslation）和
 * pending 队列（clearPendingUnknownBidirectionalTurns）；本实现先做
 * 继承兜底，投票机制留作后续增强。
 */
class BidirectionalResolver(
    var leftLanguage: LangEnum = LangEnum.ChineseSimplified,
    var rightLanguage: LangEnum = LangEnum.Spanish,
) {

    enum class Side { LEFT, RIGHT, UNKNOWN }

    data class Decision(
        val side: Side,
        /** 识别文本的语言 */
        val sourceLang: LangEnum,
        /** 要译成的语言 */
        val targetLang: LangEnum,
        /** true 表示这句的方向是靠继承上一轮推出来的，不是直接匹配 */
        val inherited: Boolean,
        /** 命中的原始 Whisper 语言码，便于排障 */
        val detectedCode: String = "",
    )

    private var lastSide: Side = Side.UNKNOWN

    /** 上次判定的方向，供 UI 显示 */
    val lastResolvedSide: Side get() = lastSide

    fun reset() {
        lastSide = Side.UNKNOWN
    }

    /**
     * @param detected Whisper 输出的语言（可能为 null / Auto）
     */
    fun resolve(detected: LangEnum?): Decision {
        val d = detected?.takeIf { it != LangEnum.Auto }

        // 1) 直接匹配语言槽
        if (d != null) {
            if (matches(d, leftLanguage)) {
                lastSide = Side.LEFT
                return Decision(
                    side = Side.LEFT,
                    sourceLang = leftLanguage,
                    targetLang = rightLanguage,
                    inherited = false,
                    detectedCode = d.code,
                )
            }
            if (matches(d, rightLanguage)) {
                lastSide = Side.RIGHT
                return Decision(
                    side = Side.RIGHT,
                    sourceLang = rightLanguage,
                    targetLang = leftLanguage,
                    inherited = false,
                    detectedCode = d.code,
                )
            }
        }

        // 2) 不匹配 → 继承上一轮（翻译鸥的 inheritedLanguage）
        if (lastSide != Side.UNKNOWN) {
            return if (lastSide == Side.LEFT) {
                Decision(
                    side = Side.LEFT,
                    sourceLang = leftLanguage,
                    targetLang = rightLanguage,
                    inherited = true,
                    detectedCode = d?.code.orEmpty(),
                )
            } else {
                Decision(
                    side = Side.RIGHT,
                    sourceLang = rightLanguage,
                    targetLang = leftLanguage,
                    inherited = true,
                    detectedCode = d?.code.orEmpty(),
                )
            }
        }

        // 3) 完全无法判定
        return Decision(
            side = Side.UNKNOWN,
            sourceLang = leftLanguage,
            targetLang = rightLanguage,
            inherited = false,
            detectedCode = d?.code.orEmpty(),
        )
    }

    /** 语言匹配：忽略地区后缀（zh == zh-CN），繁简不互认 */
    private fun matches(detected: LangEnum, slot: LangEnum): Boolean {
        if (detected == slot) return true
        val a = detected.code.lowercase()
        val b = slot.code.lowercase()
        if (a == b) return true
        // 前缀互认：zh 与 zh-CN；但 zh-CN 不能认同 zh-TW
        val aBase = a.substringBefore('-')
        val bBase = b.substringBefore('-')
        if (aBase != bBase) return false
        // 同 base 时，任一方无地区后缀即视为匹配
        return !a.contains('-') || !b.contains('-')
    }

    companion object {
        /**
         * Whisper 的 lang 码 → LangEnum。
         *
         * Whisper 返回 ISO 639-1（zh / es / en / ja …），我们枚举里中文是 zh-CN。
         * 按枚举顺序取第一个前缀命中项，因此 zh → 简体中文（而非繁体）。
         */
        fun fromWhisperCode(code: String): LangEnum? {
            val c = code.trim().lowercase()
            if (c.isEmpty()) return null
            LangEnum.entries.firstOrNull { it != LangEnum.Auto && it.code.lowercase() == c }
                ?.let { return it }
            return LangEnum.entries.firstOrNull {
                it != LangEnum.Auto && it.code.lowercase().startsWith("$c-")
            }
        }
    }
}
