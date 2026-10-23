package dev.beyonddev.beyondlingo.util

/**
 * 触发去重闸门。
 *
 * ## 为什么需要它
 *
 * 无障碍的 `TYPE_VIEW_TEXT_SELECTION_CHANGED` 不只在「选中文字」时触发：
 * - 点击输入框获得焦点
 * - 光标在输入框内移动
 * - 每敲入一个字符
 *
 * 这些都会派发该事件。若不加过滤，用户只是点了下搜索框就会弹翻译窗。
 *
 * ## 拖选过程中的处理
 *
 * 拖选一段文字时，系统会**每选一个字符**派发一次事件，
 * 文本不断增长：'c' → 'ch' → 'cha' → 'chat' …
 *
 * 若每次都触发翻译，窗口会连续刷新、明显闪烁。
 * 因此这里用「**稳定判定**」：
 * - 文本变化后进入等待窗口
 * - 等待窗口内又变了 → 说明还在拖选中，重新计时，不触发
 * - 等待窗口内没再变 → 说明选完了，触发一次
 *
 * 这与 STranslate 桌面版的「松开鼠标后才翻译」是同一思路。
 */
object TriggerGate {

    /** 拖选结束后等待多久才真正触发 */
    private const val SETTLE_DELAY_MS = 350L

    /** 同一文本的重复触发间隔 */
    private const val DEDUP_WINDOW_MS = 1500L

    /** 短于此长度的文本视为输入动作，不触发翻译 */
    private const val MIN_TEXT_LENGTH = 2

    /** 滑动窗口内允许的最大触发次数 */
    private const val MAX_BURST = 3

    /** 滑动窗口时长 */
    private const val BURST_WINDOW_MS = 3000L

    private var lastText: String? = null
    private var lastAt: Long = 0L

    /** 最近的触发时间戳，用于连发检测 */
    private val recentTriggers = ArrayDeque<Long>()

    /**
     * 判断是否应该触发翻译。
     *
     * @param text 本次事件携带的文本
     * @param now 当前时间（便于测试注入）
     * @return true 表示应当触发
     */
    @Synchronized
    fun shouldTrigger(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false

        val now = System.currentTimeMillis()

        // 1. 短文本：输入框输入、光标移动
        if (normalized.length < MIN_TEXT_LENGTH) return false

        // 2. 同一文本在时间窗内重复
        if (normalized == lastText && now - lastAt < DEDUP_WINDOW_MS) {
            return false
        }

        // 3. 连发抑制：滑动窗口内触发过多，视为误触风暴
        while (recentTriggers.isNotEmpty() && now - recentTriggers.first() > BURST_WINDOW_MS) {
            recentTriggers.removeFirst()
        }
        if (recentTriggers.size >= MAX_BURST) {
            return false
        }

        lastText = normalized
        lastAt = now
        recentTriggers.addLast(now)
        return true
    }

    /**
     * 判断这次文本变化是否属于「拖选进行中」。
     *
     * 拖选时文本是逐字增长的，前一次是后一次的前缀。
     * 若在 [SETTLE_DELAY_MS] 内出现了这种增长，说明用户还在拖，
     * 此时不应触发翻译。
     *
     * @return true 表示仍在拖选中
     */
    @Synchronized
    fun isSettling(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val previous = lastText ?: return false
        val current = text.trim()

        // 距离上次变化不足稳定窗口 → 还在拖
        if (now - lastAt < SETTLE_DELAY_MS) {
            // 且文本确实在增长（是前缀关系）
            if (current.length > previous.length && current.startsWith(previous)) {
                lastAt = now
                lastText = current
                return true
            }
        }
        return false
    }

    @Synchronized
    fun reset() {
        lastText = null
        lastAt = 0L
        recentTriggers.clear()
    }
}
