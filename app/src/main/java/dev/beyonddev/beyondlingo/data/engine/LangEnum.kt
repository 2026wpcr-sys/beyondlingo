package dev.beyonddev.beyondlingo.data.engine

/**
 * 支持的语言枚举。
 *
 * 直接照搬自 STranslate 的 `LangEnum.cs`（MIT，作者 zggsong），
 * 保留了原始的 33 种语言与顺序，便于与桌面版对齐。
 *
 * @see <a href="https://github.com/STranslate/STranslate">STranslate</a>
 */
enum class LangEnum(
    /** 简体中文显示名 */
    val labelZh: String,
    /** English display name */
    val labelEn: String,
    /** 语言代码（ISO 639-1 或 BCP-47），用于自动检测与持久化 */
    val code: String,
    /** 是否为常见语言（用于 UI 排序/置顶） */
    val common: Boolean = false,
) {
    Auto("自动检测", "Auto-detect", "auto", true),
    ChineseSimplified("简体中文", "Chinese (Simplified)", "zh-CN", true),
    ChineseTraditional("繁體中文", "Chinese (Traditional)", "zh-TW", true),
    Cantonese("粤语", "Cantonese", "yue"),
    English("英语", "English", "en", true),
    Japanese("日语", "Japanese", "ja", true),
    Korean("韩语", "Korean", "ko", true),
    French("法语", "French", "fr", true),
    Spanish("西班牙语", "Spanish", "es", true),
    Russian("俄语", "Russian", "ru", true),
    German("德语", "German", "de", true),
    Italian("意大利语", "Italian", "it"),
    Turkish("土耳其语", "Turkish", "tr"),
    PortuguesePortugal("葡萄牙语（葡萄牙）", "Portuguese (Portugal)", "pt-PT"),
    PortugueseBrazil("葡萄牙语（巴西）", "Portuguese (Brazil)", "pt-BR"),
    Vietnamese("越南语", "Vietnamese", "vi"),
    Indonesian("印度尼西亚语", "Indonesian", "id"),
    Thai("泰语", "Thai", "th"),
    Malay("马来语", "Malay", "ms"),
    Arabic("阿拉伯语", "Arabic", "ar"),
    Hindi("印地语", "Hindi", "hi"),
    MongolianCyrillic("蒙古语（西里尔文）", "Mongolian (Cyrillic)", "mn"),
    MongolianTraditional("蒙古语（传统文字）", "Mongolian (Traditional)", "mn-Mong"),
    Khmer("高棉语", "Khmer", "km"),
    NorwegianBokmal("挪威语（书面）", "Norwegian (Bokmål)", "nb"),
    NorwegianNynorsk("挪威语（新挪威语）", "Norwegian (Nynorsk)", "nn"),
    Persian("波斯语", "Persian", "fa"),
    Swedish("瑞典语", "Swedish", "sv"),
    Polish("波兰语", "Polish", "pl"),
    Dutch("荷兰语", "Dutch", "nl"),
    Ukrainian("乌克兰语", "Ukrainian", "uk"),
    Uzbek("乌兹别克语", "Uzbek", "uz"),
    Uyghur("维吾尔语", "Uyghur", "ug"),
    ;

    companion object {
        /** 从持久化的 code 字符串还原，匹配失败回退到 [Auto] */
        fun fromCode(code: String?): LangEnum =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: Auto

        /** 常见语言，用于 UI 置顶 */
        fun common(): List<LangEnum> = entries.filter { it.common }

        /** 除 [Auto] 外的全部语言，用于目标语言选择 */
        fun allTargets(): List<LangEnum> = entries.filter { it != Auto }
    }
}
