package dev.beyonddev.beyondlingo.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 应用内剪贴板写入工具。
 *
 * ## 为什么没有剪贴板监听
 *
 * Android 10（API 29）起，系统明确禁止非前台应用读取剪贴板：
 *
 * ```
 * ClipboardService: Denying clipboard access to <pkg>,
 * application is not in focus nor is it a system service for user 0
 * ```
 *
 * 这个限制对无障碍服务同样生效 —— 无障碍并不是剪贴板的特权通道。
 * 因此「在其他 App 里复制文字自动翻译」在 Android 上无法实现。
 *
 * 本项目的替代方案是**无障碍划词**：用户长按选中文字时，
 * 无障碍服务直接读取节点文本并翻译，完全绕过剪贴板。
 *
 * 本对象只负责把译文写入剪贴板，供用户粘贴使用。
 */
object ClipboardWriter {

    /**
     * 把文本写入剪贴板。
     *
     * @param label ClipData 的标签，用于系统剪贴板 UI 显示
     */
    fun copy(context: Context, text: String, label: String = "translation") {
        if (text.isBlank()) return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {
            // 剪贴板不可用时静默失败
        }
    }
}
