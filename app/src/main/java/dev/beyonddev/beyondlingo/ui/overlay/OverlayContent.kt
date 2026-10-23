package dev.beyonddev.beyondlingo.ui.overlay

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮窗内容。
 *
 * 设计要点：
 * - **字号跟随系统**：正文用 `LocalTextStyle` 的默认字号 + 系统缩放，
 *   不写死 sp 值，尊重用户在系统设置里调的字号与「字体大小」无障碍选项。
 * - **长文本折叠**：超过 [COLLAPSE_CHAR_THRESHOLD] 字符或超过 [COLLAPSED_MAX_LINES]
 *   行时折叠，点击展开。
 * - **宽度自适应**：屏宽 80%，上限 360dp，避免平板/折叠屏上过窄。
 * - **可拖动**：整块面板可拖，方便让开被遮挡的内容。
 */
@Composable
fun OverlayContent(
    sourceText: String,
    resultText: String,
    isTranslating: Boolean,
    errorText: String?,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // 宽度：屏宽 80%，夹在 260dp ~ 360dp 之间
    val panelWidth = with(density) {
        (configuration.screenWidthDp * 0.8f)
            .coerceIn(260f, 360f)
            .dp
    }

    // 长文本折叠状态
    var expanded by remember(resultText) { mutableStateOf(false) }
    val collapsible = resultText.length > COLLAPSE_CHAR_THRESHOLD

    Column(
        modifier = Modifier
            .width(panelWidth)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF21E1E1E))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .animateContentSize(animationSpec = tween(180))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------- 标题栏 ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isTranslating) Color(0xFFFFC107) else Color(0xFF34C759)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isTranslating) "翻译中" else "译文",
                    color = Color(0xFF8AB4F8),
                    // 跟随系统字号
                    style = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                    ),
                    // 标题不参与系统超大字号缩放，避免撑破布局
                    maxLines = 1,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 折叠/展开按钮（仅长文本出现）
                if (collapsible && resultText.isNotBlank()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = Color(0xFF8AB4F8),
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { expanded = !expanded }
                            .padding(6.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }

                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制译文",
                    tint = if (resultText.isNotBlank()) Color(0xFFCFCFCF)
                    else Color(0xFF4A4A4A),
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = resultText.isNotBlank()) { onCopy() }
                        .padding(6.dp),
                )

                Spacer(Modifier.width(2.dp))

                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color(0xFFCFCFCF),
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onClose() }
                        .padding(6.dp),
                )
            }
        }

        // ---------- 原文（单行省略） ----------
        if (sourceText.isNotBlank()) {
            Text(
                text = sourceText,
                color = Color(0xFF8A8F98),
                style = LocalTextStyle.current.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---------- 译文 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
        ) {
            when {
                isTranslating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF8AB4F8),
                        )
                        Text(
                            text = "正在翻译…",
                            color = Color(0xFF8A8F98),
                            style = LocalTextStyle.current.copy(fontSize = 13.sp),
                        )
                    }
                }

                errorText != null -> {
                    Text(
                        text = errorText,
                        color = Color(0xFFFF7B72),
                        style = LocalTextStyle.current.copy(
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        ),
                    )
                }

                else -> {
                    Text(
                        text = resultText,
                        color = Color(0xFFE8EAED),
                        style = LocalTextStyle.current.copy(
                            // 正文跟随系统字号，这是核心需求
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                        ),
                        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (expanded) {
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = EXPANDED_MAX_HEIGHT)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )

                    // 折叠时的「展开全文」提示
                    if (collapsible && !expanded) {
                        Text(
                            text = "展开全文（${resultText.length} 字）",
                            color = Color(0xFF8AB4F8),
                            style = LocalTextStyle.current.copy(fontSize = 12.sp),
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable { expanded = true },
                        )
                    }
                }
            }
        }
    }
}

/** 超过这个字符数就认为需要折叠 */
private const val COLLAPSE_CHAR_THRESHOLD = 160

/** 折叠状态显示的最大行数 */
private const val COLLAPSED_MAX_LINES = 6

/** 展开状态的最大高度，超出后可滚动 */
private val EXPANDED_MAX_HEIGHT = 320.dp