package dev.beyonddev.beyondlingo.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 悬浮球：常驻屏幕边缘，点击即翻译剪贴板内容。
 *
 * ## 为什么用「复制 + 点球」
 *
 * 三条自动取词路径在 Android 上都不可靠：
 * - 剪贴板监听：Android 10+ 禁止后台读取，只有应用在前台时才收到事件
 * - 无障碍划词：Chrome / 微信自绘渲染，不派发选中事件
 * - PROCESS_TEXT 菜单：Android 11+ 包可见性限制，第三方 App 看不见
 *
 * 「复制 + 点球」是唯一 100% 可靠的方式：用户复制文字后点球，
 * 此刻悬浮窗正处于焦点态，剪贴板可以正常读取。
 * 屏译、沉浸式翻译等成熟应用都走这条路。
 *
 * @param onClick 点击球体
 * @param onDrag 拖拽移动球体，参数为像素增量
 */
@Composable
fun FloatingBubble(
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(0.9f)
            .clip(CircleShape)
            .background(Color(0xFF1E4B7A))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Translate,
            contentDescription = "翻译剪贴板",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}
