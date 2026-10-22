package dev.beyonddev.beyondlingo.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.beyonddev.beyondlingo.util.TriggerGate

/**
 * 剪贴板监听器（兜底通道）。
 *
 * 为什么需要它：
 * 国内定制 ROM 常限制无障碍服务，或者某些 App（银行、支付）不上报选中事件。
 * 此时用户「复制」文本，本类捕获后触发翻译。
 *
 * Android 限制：
 * - Android 10+ 后台读剪贴板受限，**必须**运行在前台服务中
 * - Android 12+ 更严格，某些 ROM 完全禁止
 *
 * 自写过滤：
 * App 自身（翻译结果复制、主界面复制按钮）也会写剪贴板，
 * 若非用户主动操作，不应再次触发翻译，否则会出现「复制结果 → 又翻译」的循环。
 * 这里用 [SELF_WRITE_WINDOW_MS] 时间窗 + 文本比对来屏蔽。
 */
class ClipboardWatcher(private val context: Context) {

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var started = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

        val text = try {
            clip.getItemAt(0).coerceToText(context).toString().trim()
        } catch (_: Exception) {
            return@OnPrimaryClipChangedListener
        }

        if (text.isEmpty() || text.length > MAX_LENGTH) return@OnPrimaryClipChangedListener

        // 过滤掉本 App 自己写入的剪贴板内容
        if (SelfWriteMarker.shouldIgnore(text)) return@OnPrimaryClipChangedListener

        // 与无障碍通道、以及重复复制去重
        if (!TriggerGate.shouldTrigger(text)) return@OnPrimaryClipChangedListener

        FloatingWindowService.requestTranslate(
            context = context,
            text = text,
            fromSelection = false,
        )
    }

    fun start() {
        if (started) return
        try {
            clipboardManager.addPrimaryClipChangedListener(listener)
            started = true
        } catch (_: Exception) {
            // 某些 ROM 直接抛异常
        }
    }

    fun stop() {
        if (!started) return
        try {
            clipboardManager.removePrimaryClipChangedListener(listener)
        } catch (_: Exception) {
            // 忽略
        }
        started = false
    }

    private companion object {
        const val MAX_LENGTH = 10_000
    }
}

/**
 * 标记「本 App 写入的剪贴板内容」，避免自己触发自己。
 *
 * 用法：凡是 App 内部调用 `setPrimaryClip` 的地方，先调 [markSelfWrite]。
 */
object SelfWriteMarker {

    /** 写入后这段时间内的同内容变更视为自写 */
    private const val SELF_WRITE_WINDOW_MS = 2000L

    private var lastText: String? = null
    private var lastAt: Long = 0L

    @Synchronized
    fun markSelfWrite(text: String) {
        lastText = text
        lastAt = System.currentTimeMillis()
    }

    @Synchronized
    fun shouldIgnore(text: String): Boolean {
        val t = lastText ?: return false
        val elapsed = System.currentTimeMillis() - lastAt
        if (elapsed > SELF_WRITE_WINDOW_MS) {
            lastText = null
            return false
        }
        return t == text
    }

    /**
     * App 内统一写剪贴板入口：写入 + 打标记。
     */
    fun copyToClipboard(context: Context, text: String, label: String = "translation") {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            markSelfWrite(text)
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {
            // 忽略
        }
    }
}
