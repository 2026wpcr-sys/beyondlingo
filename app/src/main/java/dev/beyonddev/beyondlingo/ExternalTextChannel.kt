package dev.beyonddev.beyondlingo

/**
 * 外部取词通道。
 *
 * 用于「系统菜单翻译」：用户在任何 App 选中文字后，系统通过
 * ACTION_PROCESS_TEXT 把文字传给 MainActivity，存到这里，
 * 由 TranslateScreen 启动时消费。
 *
 * 这是 Android 最可靠的取词方式，Chrome / 微信等所有 App 都支持。
 */
object ExternalTextChannel {

    @Volatile
    private var pending: String? = null

    /** 存入待翻译文本 */
    fun put(text: String) {
        pending = text.trim()
    }

    /** 消费并清空 */
    @Synchronized
    fun consume(): String? {
        val t = pending
        pending = null
        return t
    }

    /** 是否有待翻译文本（用于判断是否跳过启动页） */
    fun hasPending(): Boolean = pending != null
}
