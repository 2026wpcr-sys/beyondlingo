package dev.beyonddev.beyondlingo.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beyonddev.beyondlingo.Brand
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.ui.component.LanguageSelector
import dev.beyonddev.beyondlingo.vm.TranslateUiState
import dev.beyonddev.beyondlingo.vm.TranslateViewModel
import dev.beyonddev.beyondlingo.data.AppSettings
import dev.beyonddev.beyondlingo.data.engine.CustomEngine
import dev.beyonddev.beyondlingo.data.engine.TranslateEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    viewModel: TranslateViewModel,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOcr: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var copiedHint by remember { mutableStateOf(false) }

    // 消费从系统菜单/分享传入的待翻译文本（ACTION_PROCESS_TEXT / SEND）
    LaunchedEffect(Unit) {
        val text = dev.beyonddev.beyondlingo.ExternalTextChannel.consume()
        if (!text.isNullOrBlank()) {
            viewModel.translateSilently(text)
        }
    }

    // 翻译成功后按设置自动复制
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is TranslateUiState.Success && settings.copyAfterTranslate) {
            copyToClipboard(context, state.output)
            copiedHint = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = Brand.APP_NAME_ZH,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (settings.autoTranslate) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "自动",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        Text(
                            text = buildString {
                                append(engineLabel(engine, settings))
                                if (settings.silentAutoTranslate) append(" · 静默")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenOcr) {
                        Icon(Icons.Default.Image, contentDescription = "图片取词")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "历史")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding(),
        ) {
            // 语言选择行
            if (settings.bilingualMode) {
                // 双语模式：只声明「母语 ⇄ 对方语言」，方向由输入内容自动判定
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LanguageSelector(
                        selected = settings.homeLang,
                        onSelect = viewModel::setHomeLang,
                        includeAuto = false,
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(
                        onClick = viewModel::swapLanguages,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "交换母语与对方语言",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    LanguageSelector(
                        selected = settings.foreignLang,
                        onSelect = viewModel::setForeignLang,
                        includeAuto = false,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 当前方向提示：中文进来译成对方语言，对方语言进来译成中文
                val direction = viewModel.previewDirection(input)
                if (direction != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "${direction.first.labelZh} → ${direction.second.labelZh}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "自动判定方向",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LanguageSelector(
                        selected = settings.defaultSourceLang,
                        onSelect = viewModel::setSourceLang,
                        includeAuto = true,
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(
                        onClick = viewModel::swapLanguages,
                        enabled = settings.defaultSourceLang != LangEnum.Auto,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "互换语言",
                            modifier = Modifier.size(20.dp),
                            tint = if (settings.defaultSourceLang != LangEnum.Auto)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }

                    LanguageSelector(
                        selected = settings.defaultTargetLang,
                        onSelect = viewModel::setTargetLang,
                        includeAuto = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 输入区
            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onInputChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp),
                placeholder = {
                    Text(
                        "输入要翻译的文本…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = viewModel::clear) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        keyboard?.hide()
                        viewModel.translateNow()
                    },
                ),
            )

            Spacer(Modifier.height(12.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (copiedHint) {
                    Text(
                        text = "已复制",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    LaunchedEffect(copiedHint) {
                        kotlinx.coroutines.delay(1500)
                        copiedHint = false
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                FilledTonalButton(
                    onClick = {
                        keyboard?.hide()
                        viewModel.translateNow()
                    },
                    enabled = input.isNotBlank() && uiState !is TranslateUiState.Translating,
                ) {
                    if (uiState is TranslateUiState.Translating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (uiState is TranslateUiState.Translating) "翻译中" else "翻译")
                }
            }

            Spacer(Modifier.height(16.dp))

            // 结果区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ResultArea(
                    state = uiState,
                    onRetry = viewModel::translateNow,
                    onCopy = { text ->
                        copyToClipboard(context, text)
                        copiedHint = true
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultArea(
    state: TranslateUiState,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
) {
    when (state) {
        is TranslateUiState.Idle, is TranslateUiState.Ready -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "输入文本开始翻译",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        is TranslateUiState.Translating -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "翻译中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is TranslateUiState.Success -> {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 6 }),
                exit = fadeOut(),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.engineName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (state.elapsedMs > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${state.elapsedMs}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.6f),
                                    )
                                }
                                state.detectedLang?.let { lang ->
                                    if (lang != LangEnum.Auto) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "识别为 ${lang.labelZh}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { onCopy(state.output) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = state.output,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }

        is TranslateUiState.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.error),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "翻译失败",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (state.retryable) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}


/**
 * 引擎显示名。
 *
 * 自定义引擎会根据用户选中的预设显示具体服务名（如「智谱 GLM」），
 * 而不是笼统的「自定义引擎」。
 */
private fun engineLabel(
    engine: TranslateEngine,
    settings: AppSettings,
): String {
    return if (engine is CustomEngine) {
        engine.displayNameFor(settings.configFor(engine.id))
    } else {
        engine.displayName
    }
}
private fun copyToClipboard(context: Context, text: String) {
    dev.beyonddev.beyondlingo.service.ClipboardWriter.copy(context, text)
}