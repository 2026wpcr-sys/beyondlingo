package dev.beyonddev.beyondlingo.util

/**
 * 触发去重闸门。
 *
 * 无障碍与剪贴板两条通道可能同时捕获同一段文本，
 * 这里保证同一文本在 [DEDUP_WINDOW_MS] 内只触发一次。
 */
object TriggerGate {

    private const val DEDUP_WINDOW_MS = 1500L

    private var lastText: String? = null
    private var lastAt: Long = 0L

    @Synchronized
    fun shouldTrigger(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false

        val now = System.currentTimeMillis()
        if (normalized == lastText && now - lastAt < DEDUP_WINDOW_MS) {
            return false
        }

        lastText = normalized
        lastAt = now
        return true
    }

    @Synchronized
    fun reset() {
        lastText = null
        lastAt = 0L
    }
}
