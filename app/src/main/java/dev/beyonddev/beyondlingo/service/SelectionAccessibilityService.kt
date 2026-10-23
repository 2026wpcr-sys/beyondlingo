package dev.beyonddev.beyondlingo.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.beyonddev.beyondlingo.util.TriggerGate

/**
 * 无障碍服务：监听选中文本并触发翻译。
 *
 * 对应 STranslate 桌面版的「划词翻译」：选中文本即出译文。
 *
 * ## 触发机制
 *
 * 监听 `TYPE_VIEW_TEXT_SELECTION_CHANGED`，用户长按选中文字时
 * 系统会派发该事件，本服务从中提取选中文本并触发翻译。
 *
 * ## 为什么不用剪贴板
 *
 * Android 10+ 禁止非前台应用读剪贴板，无障碍服务也不例外。
 * 划词是 Android 上唯一稳定的跨应用取词方式。
 *
 * ## 兼容性
 *
 * - 部分 App（微信、银行类）不上报 selection 索引 → 退化为取整个节点文本
 * - 部分 App 完全不派发选中事件 → 该 App 内无法划词
 * - 国内定制 ROM 可能限制后台运行 → 需加入自启动白名单
 */
class SelectionAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return

        // 忽略自己 App 内的事件，避免自我触发
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName) return

        val node = event.source ?: return

        val selected = extractSelection(node) ?: return

        if (selected.length < MIN_LENGTH) return
        if (selected.length > MAX_LENGTH) return

        // 纯空格/换行/标点不触发
        if (!hasMeaningfulContent(selected)) return

        // 同一段文本短时间内不重复触发
        if (!TriggerGate.shouldTrigger(selected)) return

        FloatingWindowService.requestTranslate(
            context = applicationContext,
            text = selected,
            fromSelection = true,
        )
    }

    /**
     * 从节点提取选中文本。
     *
     * 分两级：
     * 1. 有 selection 索引 → 精确截取（最可靠）
     * 2. 无索引 → 取整个节点文本（仅当长度合理，避免整页文本）
     */
    private fun extractSelection(node: AccessibilityNodeInfo): String? {
        val full = node.text?.toString()

        val start = node.textSelectionStart
        val end = node.textSelectionEnd

        // 优先：有明确的选中范围
        if (full != null && start >= 0 && end > start && end <= full.length) {
            val piece = full.substring(start, end).trim()
            if (piece.isNotEmpty()) return piece
        }

        // 退化：没有 selection 索引时，取节点文本
        // 限制长度，因为整页文本（如网页正文）不适合当划词结果
        val fallback = full?.trim()
        if (fallback != null && fallback.isNotEmpty() && fallback.length <= FALLBACK_MAX_LENGTH) {
            return fallback
        }

        return null
    }

    /** 是否包含有意义的字符（字母、数字、CJK 等），排除纯标点 */
    private fun hasMeaningfulContent(text: String): Boolean {
        for (ch in text) {
            if (ch.isLetterOrDigit()) return true
        }
        return false
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    companion object {
        /** 触发翻译的最短文本长度 */
        private const val MIN_LENGTH = 1

        /** 单选节点文本的最大长度（超过视为整页文本，不是划词） */
        private const val MAX_LENGTH = 5000

        /** 无 selection 索引时的退化上限，比 MAX_LENGTH 更严格 */
        private const val FALLBACK_MAX_LENGTH = 200

        /** 供设置页查询服务是否在运行 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
