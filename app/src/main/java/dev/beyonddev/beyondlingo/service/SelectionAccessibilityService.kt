package dev.beyonddev.beyondlingo.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.beyonddev.beyondlingo.BuildConfig
import dev.beyonddev.beyondlingo.util.TriggerGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无障碍服务：辅助划词翻译。
 *
 * ## 定位：辅助通道
 *
 * 主要取词方式是**系统菜单翻译**（ACTION_PROCESS_TEXT）——用户选中文字后
 * 点系统菜单里的「无界译」，100% 可靠，所有 App 都支持。
 *
 * 本服务是辅助：在部分 App 里做到「选中即弹窗」，无需再点菜单。
 *
 * ## 已知限制（实测确认）
 *
 * - **Chrome / 微信不派发选中事件**：它们用自绘渲染，文字选区不通过
 *   无障碍暴露。实测 Chrome 只派发 `TYPE_WINDOW_CONTENT_CHANGED`，
 *   微信长按时 `event.text` 为空。这些 App 请用系统菜单翻译。
 * - **WhatsApp 可用**：长按事件带完整文本（`TYPE_VIEW_LONG_CLICKED`）。
 * - 国内定制 ROM 可能限制后台运行 → 需加入自启动白名单。
 */
class SelectionAccessibilityService : AccessibilityService() {

    /** 用于延迟派发翻译任务 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        // 只在白名单 App 内启用划词。
        //
        // 实测结论（realme / Android 16）：
        // - WhatsApp 派发 TYPE_VIEW_LONG_CLICKED 且带完整消息文本 → 可用
        // - Chrome 不派发任何选中事件，且自动弹窗会打断浏览 → 禁用
        // - 微信长按事件 text 为空，取不到文字 → 禁用
        //
        // 白名单以外的 App 统一走「复制 + 点悬浮球」。
        if (!isSelectionAllowed(pkg)) {
            Log.v(TAG, "skip pkg=$pkg (not in selection whitelist)")
            return
        }

        when (event.eventType) {
            // 标准选中事件
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleSelectionEvent(event)
            }

            // 长按事件（WhatsApp 等自绘消息气泡不产生选区，只产生长按）
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                handleLongClickEvent(event)
            }
        }
    }

    /**
     * 划词白名单。
     *
     * 只对「实测能可靠取到文字」的 App 开启自动划词，
     * 其他 App 一律走「复制 + 点悬浮球」，避免误触发和无效弹窗。
     */
    private fun isSelectionAllowed(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        // 精确匹配，或匹配前缀以覆盖多开/克隆应用
        return SELECTION_WHITELIST.any { pkg == it || pkg.startsWith("$it.") }
    }

    /**
     * 处理标准选中事件。
     *
     * ## 关键判据
     *
     * `TYPE_VIEW_TEXT_SELECTION_CHANGED` 不只在「选中文字」时触发：
     * 点击输入框、焦点变化、光标移动、每次键入都会派发。
     *
     * 真正的「划词」有两个可靠特征：
     * 1. 节点有有效选中范围 `textSelectionEnd > textSelectionStart`
     * 2. 该节点处于**选中态**（`isSelected == true`）
     *
     * 输入框满足不了第 1 条（光标是一个位置，不是一个范围），
     * 因此据此可以准确区分。
     */
    private fun handleSelectionEvent(event: AccessibilityEvent) {
        Log.d(TAG, "SELECTION pkg=${event.packageName}")

        val node = resolveSourceNode(event)

        // 诊断：记录节点的原始属性，用于定位误触发
        if (node != null) {
            val selStart = node.textSelectionStart
            val selEnd = node.textSelectionEnd
            Log.d(
                TAG,
                "node: isEditable=${node.isEditable} isSelected=${node.isSelected} " +
                    "isFocused=${node.isFocused} selRange=$selStart..$selEnd " +
                    "text='${node.text?.toString()?.take(40)}' " +
                    "desc='${node.contentDescription?.toString()?.take(40)}'",
            )
        } else {
            Log.w(TAG, "node is null")
        }

        // ---- 判据：文本必须是「被选中的内容」而不是「焦点所在的内容」 ----
        //
        // 不能依赖 textSelectionStart/End：Chrome、微信等自绘控件不填这两个字段，
        // 拖选正文时它们恒为 -1，用它做判据会把真划词也挡掉。
        //
        // 改为看 event.text 与节点文本的关系：
        // - 拖选时 event.text 是节点文本的**一部分**（子串）→ 是真划词
        // - 点击输入框时 event.text 是节点的**全部**（等于）→ 是焦点变化
        val eventText = extractFromEvent(event)

        val nodeText = node?.text?.toString()

        val isSubSelection = eventText != null && nodeText != null &&
            eventText.isNotBlank() && eventText.length < nodeText.length &&
            nodeText.contains(eventText)

        val isWholeNode = eventText != null && nodeText != null &&
            eventText.trim() == nodeText.trim()

        if (eventText != null && nodeText != null && !isSubSelection && isWholeNode) {
            // 事件携带的就是整个节点文本 —— 说明是点击/聚焦，不是选中其中一段
            Log.d(TAG, "reject: event text == whole node (focus change, not a sub-selection)")
            return
        }

        // 输入框（EditText）专有防护：
        // 输入框获得焦点、光标移动、敲字都会派发该事件，
        // 但用户不会在输入框里「划词」——那里只有光标没有选区。
        // 唯一的例外是用户确实拖选了输入框里已有的文字，
        // 那种情况 textSelectionStart/End 会有有效值。
        if (node?.isEditable == true) {
            val s = node.textSelectionStart
            val e = node.textSelectionEnd
            val hasSelectionRange = s >= 0 && e > s
            if (!hasSelectionRange) {
                Log.d(TAG, "reject: editable node without selection range (input box focus)")
                return
            }
        }

        // 优先从事件取（浏览器把选中内容放在 event.text 里）
        eventText?.let { text ->
            Log.d(TAG, "from event.text: '${text.take(60)}'")
            dispatchIfValid(text, source = "event")
            return
        }

        // 回退：节点树
        if (node == null) return

        val selected = extractSelection(node)
        if (selected == null) {
            Log.w(TAG, "extractSelection null")
            return
        }

        Log.d(TAG, "from node: '${selected.take(60)}'")
        dispatchIfValid(selected, source = "node")
    }

    /**
     * 处理长按事件。
     *
     * WhatsApp / 微信等应用的消息气泡是自绘 View，长按后只弹菜单，
     * 不产生选区事件。但长按事件的 `text` 里通常带有被长按的完整文本。
     */
    /**
     * 处理长按事件。
     *
     * WhatsApp / 微信等应用的消息气泡是自绘 View，长按后只弹菜单，
     * 不产生选区事件。但长按事件的 `text` 里通常带有被长按的完整文本。
     *
     * 误触发来源：长按应用名、图标、搜索框等固定文案。
     * 对策：要求文本长度达到 [LONG_CLICK_MIN_LENGTH]，
     * 且不能是明显的短词/标题。
     */
    private fun handleLongClickEvent(event: AccessibilityEvent) {
        Log.d(TAG, "LONG_CLICK pkg=${event.packageName}")

        // 长按事件的 text 通常就是被长按的那个元素文本
        val fromEvent = extractFromEvent(event)
        if (fromEvent != null) {
            Log.d(TAG, "long-click text: '${fromEvent.take(60)}' len=${fromEvent.length}")
            if (fromEvent.length >= LONG_CLICK_MIN_LENGTH) {
                dispatchIfValid(fromEvent, source = "longclick")
                return
            }
            Log.d(TAG, "reject(longclick): too short (${fromEvent.length} < $LONG_CLICK_MIN_LENGTH)")
            return
        }

        // 回退：从节点取
        val node = resolveSourceNode(event)
        if (node != null) {
            val text = node.text?.toString()?.trim()
                ?: node.contentDescription?.toString()?.trim()
            Log.d(
                TAG,
                "node: isEditable=${node.isEditable} " +
                    "text='${node.text?.toString()?.take(40)}' " +
                    "desc='${node.contentDescription?.toString()?.take(40)}'",
            )
            if (!text.isNullOrEmpty() && text.length >= LONG_CLICK_MIN_LENGTH) {
                Log.d(TAG, "long-click node text: '${text.take(60)}'")
                dispatchIfValid(text, source = "longclick-node")
                return
            }
            Log.d(TAG, "reject(longclick-node): too short")
            return
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

    /**
     * 统一校验并触发翻译。
     *
     * ## 延迟派发
     *
     * 拖选一段文字时，系统每选一个字符就派发一次事件
     * （'c' → 'ch' → 'cha' …）。若每次都立即翻译，
     * 窗口会连续刷新、明显闪烁。
     *
     * 因此这里延迟 [SETTLE_DELAY_MS] 再派发：
     * - 延迟期间又来了新文本 → 取消旧的，用新的重新计时
     * - 延迟期间没新文本 → 说明用户选完了，正式触发
     */
    private fun dispatchIfValid(selected: String, source: String) {
        if (selected.length < MIN_LENGTH) {
            Log.d(TAG, "reject($source): too short")
            return
        }
        if (selected.length > MAX_LENGTH) {
            Log.d(TAG, "reject($source): too long")
            return
        }
        if (!hasMeaningfulContent(selected)) {
            Log.d(TAG, "reject($source): no meaningful content")
            return
        }
        if (!TriggerGate.shouldTrigger(selected)) {
            Log.d(TAG, "reject($source): deduped")
            return
        }

        // 取消上一次待派发的任务（用户还在继续拖选）
        pendingDispatch?.cancel()
        pendingDispatch = scope.launch {
            delay(SETTLE_DELAY_MS)
            Log.i(TAG, "TRIGGER[$source]: '${selected.take(60)}'")
            FloatingWindowService.requestTranslate(
                context = applicationContext,
                text = selected,
                fromSelection = true,
            )
        }
    }

    /** 待派发的翻译任务，拖选过程中不断被替换 */
    private var pendingDispatch: Job? = null

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
        pendingDispatch?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BeyondLingoA11y"

        /**
         * 允许自动划词的 App 白名单。
         *
         * 只保留实测能可靠取到文字的 App：
         * - com.whatsapp：长按消息时 event.text 带完整消息内容
         *
         * 其他 App（Chrome、微信、系统设置等）一律不自动划词，
         * 用户复制文字后点悬浮球即可翻译。
         */
        private val SELECTION_WHITELIST = setOf(
            "com.whatsapp",
        )

        /**
         * 拖选结束后的稳定等待时长。
         *
         * 拖选时系统每选一个字符就派发一次事件，
         * 等一小段时间确认不再变化，才认为用户选完了。
         */
        private const val SETTLE_DELAY_MS = 400L

        /** 触发翻译的最短文本长度 */
        private const val MIN_LENGTH = 1

        /**
         * 长按触发所需的最短长度。
         *
         * 长按时拿到的是元素完整文本，短的往往是应用名、图标名、
         * 搜索框提示语等固定文案，不是用户想翻译的内容。
         */
        private const val LONG_CLICK_MIN_LENGTH = 8

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
