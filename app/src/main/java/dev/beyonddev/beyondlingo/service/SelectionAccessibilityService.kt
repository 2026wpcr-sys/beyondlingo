package dev.beyonddev.beyondlingo.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.beyonddev.beyondlingo.util.TriggerGate

/**
 * 无障碍服务：监听应用内选中文本，触发划词翻译。
 *
 * 对应 STranslate 的 Windows 全局钩子 + `Ctrl+C+C` 方案，
 * 在 Android 上改用 `TYPE_VIEW_TEXT_SELECTION_CHANGED` 事件。
 *
 * 局限性：
 * - 部分 App（微信、银行类）不上报选中范围，只能退化为取整个节点文本
 * - 国内定制 ROM 可能限制后台运行，需用户加入自启动白名单
 */
class SelectionAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return

        // 忽略自己 App 内的事件，避免自我触发
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName) return

        val node = event.source ?: return
        val selected = try {
            extractSelection(node)
        } finally {
            // 不回收 node：系统持有所有权
        } ?: return

        if (selected.length < 2) return
        if (selected.length > MAX_LENGTH) return

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
     * 优先用 selection 索引截取；部分 App 不上报索引，
     * 退化为返回整个节点文本（此时可能触发过多，因此限制长度）。
     */
    private fun extractSelection(node: AccessibilityNodeInfo): String? {
        val full = node.text?.toString()?.trim()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd

        if (full != null && start >= 0 && end > start && end <= full.length) {
            return full.substring(start, end).trim().takeIf { it.isNotEmpty() }
        }

        // 退化路径：仅当节点文本较短时才认为是「划词」而非「整页文本」
        return full?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
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
        private const val MAX_LENGTH = 5000

        /** 供设置页查询服务是否在运行 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
