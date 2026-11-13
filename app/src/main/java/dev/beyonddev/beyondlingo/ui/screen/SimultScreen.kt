package dev.beyonddev.beyondlingo.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.beyonddev.beyondlingo.data.asr.BidirectionalResolver
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.vm.SimultViewModel
import dev.beyonddev.beyondlingo.vm.SubtitleLine

/**
 * 同声传译全屏字幕。
 *
 * 交互：点「开始」请求录音权限 → 持续识别 → 每段结束自动翻译上屏。
 * 字幕布局：原文在上（小、淡），译文在下（大、醒目）—— 与翻译鸥一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimultScreen(
    viewModel: SimultViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speakingSegmentId by viewModel.speakingSegmentId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 双向语言对（默认 中文 ⇄ 西班牙语）
    var leftLang by remember { mutableStateOf(LangEnum.ChineseSimplified) }
    var rightLang by remember { mutableStateOf(LangEnum.Spanish) }

    // 权限请求前记下用户点的是哪个语言按钮
    var pendingSide by remember {
        mutableStateOf(BidirectionalResolver.Side.LEFT)
    }

    // 正在编辑原文的卡片 segmentId；null 表示没有编辑对话框
    var editingSegmentId by remember { mutableStateOf<Int?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startConversation(leftLang, rightLang, pendingSide)
    }

    // 新字幕到达时自动滚到底部
    LaunchedEffect(state.lines.size, state.partial) {
        val total = state.lines.size + if (state.partial.isNotEmpty()) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对话翻译") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        if (viewModel.canDrawOverlay()) {
                            viewModel.startOverlay()
                        } else {
                            // 部分 ROM（如 realme）不响应 MANAGE_OVERLAY_PERMISSION，
                            // 逐级降级：悬浮窗授权页 → 应用详情页 → 提示文案
                            var opened = false
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                opened = true
                            }
                            if (!opened) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:" + context.packageName),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                    opened = true
                                }
                            }
                            viewModel.notifyOverlayNeedsPermission(!opened)
                        }
                    }) { Text("悬浮") }
                    TextButton(onClick = onOpenModels) { Text("模型") }
                    if (state.lines.isNotEmpty()) {
                        TextButton(onClick = viewModel::clear) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            // ---- 状态提示 ----
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // ---- 字幕区 ----
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading -> CenterHint("正在加载语音模型…", showSpinner = true)

                    state.lines.isEmpty() && state.partial.isEmpty() -> CenterHint(
                        if (state.running) "请开始说话，说完停顿一下自动翻译…"
                        else "选择谁来发言，点击对应语言按钮"
                    )

                    else -> LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.lines, key = { it.segmentId }) { line ->
                            SubtitleCard(
                                line = line,
                                speaking = speakingSegmentId == line.segmentId,
                                onSpeak = { viewModel.speakLine(line.segmentId) },
                                onEdit = { editingSegmentId = line.segmentId },
                            )
                        }

                        // 当前正在说的（临时字幕）
                        if (state.partial.isNotEmpty()) {
                            item(key = "partial") {
                                Text(
                                    text = state.partial,
                                    fontSize = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            // ---- 双向语言对 ----
            if (!state.running) {
                DirectionBar(
                    left = leftLang,
                    right = rightLang,
                    enabled = !state.loading,
                    onLeftChange = { leftLang = it },
                    onRightChange = { rightLang = it },
                )
            }

            // ---- 控制区：两个语言按钮，点哪个就是谁在说 ----
            if (state.running) {
                Button(
                    onClick = viewModel::stop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .height(52.dp),
                ) {
                    Text("结束这段", fontSize = 16.sp)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.startConversation(
                                    leftLang, rightLang, BidirectionalResolver.Side.LEFT
                                )
                            } else {
                                pendingSide = BidirectionalResolver.Side.LEFT
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !state.loading,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(leftLang.labelZh, fontSize = 16.sp)
                            Text("译成${rightLang.labelZh}", fontSize = 11.sp)
                        }
                    }

                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.startConversation(
                                    leftLang, rightLang, BidirectionalResolver.Side.RIGHT
                                )
                            } else {
                                pendingSide = BidirectionalResolver.Side.RIGHT
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !state.loading,
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(rightLang.labelZh, fontSize = 16.sp)
                            Text("译成${leftLang.labelZh}", fontSize = 11.sp)
                        }
                    }
                }
            }

            // 仅 debug 构建：无麦克风环境下验证链路（模拟器）
            if (dev.beyonddev.beyondlingo.BuildConfig.DEBUG && !state.running && !state.loading) {
                TextButton(
                    onClick = viewModel::startTestReplay,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("[测试] 回放示例音频验证链路", fontSize = 13.sp)
                }
            }

            Text(
                text = if (state.cloudAsr) {
                    "双方轮流说，说完停顿即翻译；识别走云端（Groq），音频会上传"
                } else {
                    "双方轮流说，说完停顿即翻译；音频在设备本地识别"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )
        }
    }

    // 编辑原文对话框：确认后按这条卡片自己的方向重新翻译
    editingSegmentId?.let { segId ->
        val line = state.lines.firstOrNull { it.segmentId == segId }
        if (line == null) {
            editingSegmentId = null
        } else {
            EditOriginalDialog(
                initial = line.original,
                onDismiss = { editingSegmentId = null },
                onConfirm = { newText ->
                    viewModel.editLineOriginal(segId, newText)
                    editingSegmentId = null
                },
            )
        }
    }
}

@Composable
private fun SubtitleCard(
    line: SubtitleLine,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // 原文行：左侧文字 + 铅笔编辑按钮
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    // 说话方标签（仅双向模式显示）：直接显示语种，不写「我方/对方」
                    if (line.side != BidirectionalResolver.Side.UNKNOWN) {
                        val isMe = line.side == BidirectionalResolver.Side.LEFT
                        val langLabel = line.directionLabel.substringBefore("→").trim()
                        if (langLabel.isNotEmpty()) {
                            Text(
                                text = langLabel,
                                fontSize = 10.sp,
                                color = if (isMe) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = line.original,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 铅笔：编辑原文 → 重新翻译
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑原文",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // 译文行：左侧译文 + ▶ 朗读按钮（读目标语言）
            when {
                line.translating -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(12.dp).height(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "翻译中…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = line.translated.orEmpty(),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (line.failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // ▶/■：朗读这条译文（双向：读方向箭头右侧的语种）；
                    // 播放中变为停止按钮
                    IconButton(
                        onClick = onSpeak,
                        enabled = !line.failed && !line.translated.isNullOrBlank(),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (speaking) {
                                Icons.Default.Stop
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = if (speaking) "停止朗读" else "朗读译文",
                            tint = when {
                                line.failed || line.translated.isNullOrBlank() ->
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                speaking -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 编辑原文对话框：确认后重新翻译并原地更新卡片 */
@Composable
private fun EditOriginalDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑原文") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != initial,
            ) { Text("重新翻译") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 双向语言对选择：我方 ⇄ 对方。
 *
 * 交互刻意做成「点一下换语言」而不是复杂选择器：
 * 同传场景下用户手是空的，需要一眼看懂、一点就改。
 */
@Composable
private fun DirectionBar(
    left: LangEnum,
    right: LangEnum,
    enabled: Boolean,
    onLeftChange: (LangEnum) -> Unit,
    onRightChange: (LangEnum) -> Unit,
) {
    var leftOpen by remember { mutableStateOf(false) }
    var rightOpen by remember { mutableStateOf(false) }
    val choices = remember {
        listOf(
            LangEnum.ChineseSimplified,
            LangEnum.Spanish,
            LangEnum.English,
            LangEnum.Japanese,
            LangEnum.Korean,
            LangEnum.French,
            LangEnum.German,
            LangEnum.Russian,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { leftOpen = true }, enabled = enabled) {
                Text(left.labelZh, fontSize = 13.sp)
            }
            DropdownMenu(expanded = leftOpen, onDismissRequest = { leftOpen = false }) {
                choices.filter { it != right }.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.labelZh) },
                        onClick = { leftOpen = false; onLeftChange(lang) },
                    )
                }
            }
        }

        Text("⇄", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Box {
            TextButton(onClick = { rightOpen = true }, enabled = enabled) {
                Text(right.labelZh, fontSize = 13.sp)
            }
            DropdownMenu(expanded = rightOpen, onDismissRequest = { rightOpen = false }) {
                choices.filter { it != left }.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.labelZh) },
                        onClick = { rightOpen = false; onRightChange(lang) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, showSpinner: Boolean = false) {    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = text,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
