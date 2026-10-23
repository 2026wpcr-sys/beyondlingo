package dev.beyonddev.beyondlingo.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.beyonddev.beyondlingo.BuildConfig
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

        // 诊断：记录所有收到的事件类型（便于排查为何某些 App 不触发）
        if (BuildConfig.DEBUG) {
            Log.v(
                TAG,
                "event type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                    "pkg=${event.packageName} text=${event.text?.joinToString()?.take(30)}"
            )
        }

        // 忽略自己 App 内的事件，避免自我触发
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName) return

        when (event.eventType) {
            // 标准选中事件（Chrome、记事本、输入框等）
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleSelectionEvent(event)
            }

            // 长按事件（WhatsApp、微信等自绘消息气泡不产生选区，只产生长按）
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                handleLongClickEvent(event)
            }
        }
    }

    /**
     * 处理标准选中事件。
     *
     * 优先从 `event.text` 取（浏览器把选中内容放在这里），
     * 拿到文本，再回退到节点树。
     */
    private fun handleSelectionEvent(event: AccessibilityEvent) {
        Log.d(TAG, "SELECTION event pkg=${event.packageName}")

        // 优先从事件取
        extractFromEvent(event)?.let { text ->
            Log.d(TAG, "from event.text: '${text.take(60)}'")
            dispatchIfValid(text)
            return
        }

        // 回退：节点树
        val node = resolveSourceNode(event)
        if (node == null) {
            Log.w(TAG, "no node resolved")
            return
        }

        val selected = extractSelection(node)
        if (selected == null) {
            Log.w(TAG, "extractSelection null")
            return
        }

        Log.d(TAG, "from node: '${selected.take(60)}'")
        dispatchIfValid(selected)
    }

    /**
     * 处理长按事件。
     *
     * WhatsApp / 微信等应用的消息气泡是自绘 View，长按后只弹菜单，
     * 不产生选区事件。但长按事件的 `text` 里通常带有被长按的完整文本。
     */
    private fun handleLongClickEvent(event: AccessibilityEvent) {
        Log.d(TAG, "LONG_CLICK event pkg=${event.packageName}")

        // 长按事件的 text 通常就是被长按的那个元素文本
        val fromEvent = extractFromEvent(event)
        if (fromEvent != null && fromEvent.length in MIN_LENGTH..MAX_LENGTH) {
            Log.d(TAG, "long-click text: '${fromEvent.take(60)}'")
            dispatchIfValid(fromEvent)
            return
        }

        // 回退：从节点取
        val node = resolveSourceNode(event)
        if (node != null) {
            val text = node.text?.toString()?.trim()
                ?: node.contentDescription?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                Log.d(TAG, "long-click node text: '${text.take(60)}'")
                dispatchIfValid(text)
                return
            }
        }

        Log.d(TAG, "long-click: no usable text")
    }

    /**
     * 直接从事件里提取选中文本。
     *
     * Chrome / WebView 的选中事件通常把文本放在 `event.text` 中，
     * 这比遍历节点树更可靠（节点树常因安全策略不可见）。
     */
    private fun extractFromEvent(event: AccessibilityEvent): String? {
        val items = event.text
        if (items.isNullOrEmpty()) return null

        val sb = StringBuilder()
        for (item in items) {
            val s = item?.toString()?.trim()
            if (!s.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }

        val result = sb.toString().trim()
        return result.ifEmpty { null }
    }

    /** 统一校验并触发翻译 */
    private fun dispatchIfValid(selected: String) {
        if (selected.length < MIN_LENGTH) return
        if (selected.length > MAX_LENGTH) return
        if (!hasMeaningfulContent(selected)) {
            Log.d(TAG, "no meaningful content, skip")
            return
        }
        if (!TriggerGate.shouldTrigger(selected)) {
            Log.d(TAG, "deduped")
            return
        }

        Log.i(TAG, "TRIGGER: '${selected.take(60)}'")
        FloatingWindowService.requestTranslate(
            context = applicationContext,
            text = selected,
            fromSelection = true,
        )
    }

    /**
     * 解析事件对应的节点。
     *
     * `event.source` 在多数情况下为 null（Android 的已知行为），
     * 因此需要多级回退：
     * 1. `event.source`
     * 2. 用 `event.getSource()` 的 node
     * 3. 从 rootInActiveWindow 里按文本/选中范围查找
     */
    private fun resolveSourceNode(event: AccessibilityEvent): AccessibilityNodeInfo? {
        // 1. 直接用 source（可能是父容器，文本在子节点里，所以不要求 text 非空）
        event.source?.let { return it }

        // 2. 从当前活动窗口的根节点递归查找「有选中范围」的节点
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        return findNodeWithSelection(root, depth = 0)
    }

    /**
     * 递归查找有文本选中范围的节点。
     *
     * 优先返回 textSelectionStart < textSelectionEnd 的节点，
     * 这是「用户真的选中了文字」的最可靠标志。
     */
    private fun findNodeWithSelection(
        node: AccessibilityNodeInfo?,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null

        // 命中条件：有文本 + 有效选中范围
        val text = node.text?.toString()
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd

        if (!text.isNullOrEmpty() && selStart >= 0 && selEnd > selStart && selEnd <= text.length) {
            return node
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val hit = findNodeWithSelection(child, depth + 1)
            if (hit != null) return hit
        }

        return null
    }

    /**
     * 从节点提取选中文本。
     *
     * Chrome 等浏览器渲染的网页内容，文字可能不在 `node.text` 里，
     * 而在以下位置：
     * - `contentDescription`
     * - `text`（EditText 类）
     * - 子节点的文本拼接
     *
     * 因此这里做多级尝试。
     */
    private fun extractSelection(node: AccessibilityNodeInfo): String? {
        // 尝试 1：从 text + selection 索引精确截取
        node.text?.toString()?.let { full ->
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (start >= 0 && end > start && end <= full.length) {
                val piece = full.substring(start, end).trim()
                if (piece.isNotEmpty()) return piece
            }
            // 有 text 但无索引：若文本不太长则取整段
            val trimmed = full.trim()
            if (trimmed.isNotEmpty() && trimmed.length <= FALLBACK_MAX_LENGTH) {
                return trimmed
            }
        }

        // 尝试 2：contentDescription
        node.contentDescription?.toString()?.trim()?.let {
            if (it.isNotEmpty() && it.length <= FALLBACK_MAX_LENGTH) return it
        }

        // 尝试 3：拼接子节点文本
        val fromChildren = collectChildText(node, depth = 0)
        if (fromChildren.isNotEmpty()) return fromChildren

        return null
    }

    /**
     * 递归拼接子节点文本。
     *
     * 用于处理浏览器等把文字拆散在多个子节点的情况。
     */
    private fun collectChildText(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > MAX_DEPTH) return ""
        val sb = StringBuilder()

        node.text?.toString()?.trim()?.let {
            if (it.isNotEmpty()) sb.append(it)
        }

        for (i in 0 until node.childCount) {
            if (sb.length > FALLBACK_MAX_LENGTH) break
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val childText = collectChildText(child, depth + 1)
            if (childText.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(childText)
            }
        }

        return sb.toString().trim()
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
        private const val TAG = "BeyondLingoA11y"

        /** 触发翻译的最短文本长度 */
        private const val MIN_LENGTH = 1

        /** 单选节点文本的最大长度（超过视为整页文本，不是划词） */
        private const val MAX_LENGTH = 5000

        /** 无 selection 索引时的退化上限，比 MAX_LENGTH 更严格 */
        private const val FALLBACK_MAX_LENGTH = 200

        /** 递归查找节点的最大深度，防止深层树卡死 */
        private const val MAX_DEPTH = 30

        /** 供设置页查询服务是否在运行 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
