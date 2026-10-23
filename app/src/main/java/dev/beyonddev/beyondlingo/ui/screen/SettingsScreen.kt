package dev.beyonddev.beyondlingo.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beyonddev.beyondlingo.data.engine.CustomEnginePresets
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.EnginePreset
import dev.beyonddev.beyondlingo.data.engine.EngineRegistry
import dev.beyonddev.beyondlingo.data.engine.TranslateEngine
import dev.beyonddev.beyondlingo.vm.TranslateViewModel
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.text.style.TextAlign
import dev.beyonddev.beyondlingo.Brand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: TranslateViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 权限/服务状态需要手动刷新
    var hasOverlayPermission by remember { mutableStateOf(canDrawOverlays(context)) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }

    LaunchedEffect(Unit) {
        hasOverlayPermission = canDrawOverlays(context)
        accessibilityEnabled = isAccessibilityEnabled(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ============ 引擎 ============
            SectionTitle("翻译引擎")

            EngineRegistry.all().forEach { engine ->
                EngineCard(
                    engine = engine,
                    selected = settings.engineId == engine.id,
                    config = settings.configFor(engine.id),
                    onSelect = { viewModel.setEngine(engine.id) },
                    onConfigChange = { cfg -> viewModel.saveEngineConfig(engine.id, cfg) },
                )
            }

            Spacer(Modifier.height(4.dp))

            // ============ 翻译触发方式 ============
            SectionTitle("翻译触发方式")

            SettingsCard {
                AutoTranslateSummary(
                    inputDebounce = settings.autoTranslate,
                    selection = settings.selectionTranslateEnabled,
                    silent = settings.silentAutoTranslate,
                    delayMs = settings.autoTranslateDelayMs,
                    hasOverlay = hasOverlayPermission,
                    hasAccessibility = accessibilityEnabled,
                )
            }

            Spacer(Modifier.height(4.dp))

            // ============ 翻译行为 ============
            SectionTitle("翻译行为")

            SettingsCard {
                SwitchRow(
                    title = "输入后自动翻译",
                    subtitle = "停止输入 ${settings.autoTranslateDelayMs}ms 后自动触发",
                    checked = settings.autoTranslate,
                    onCheckedChange = viewModel::setAutoTranslate,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SwitchRow(
                    title = "翻译后自动复制",
                    subtitle = "结果直接写入剪贴板",
                    checked = settings.copyAfterTranslate,
                    onCheckedChange = viewModel::setCopyAfterTranslate,
                )
            }

            Spacer(Modifier.height(4.dp))

            // ============ 划词翻译 ============
            SectionTitle("划词翻译")

            SettingsCard {
                SwitchRow(
                    title = "无障碍划词",
                    subtitle = if (accessibilityEnabled)
                        "已开启，长按选中文字即可翻译"
                    else "需在系统设置中启用无障碍服务",
                    checked = settings.selectionTranslateEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !accessibilityEnabled) {
                            openAccessibilitySettings(context)
                        } else {
                            viewModel.setSelectionTranslate(enabled)
                        }
                    },
                )

                if (!accessibilityEnabled) {
                    PermissionHint(
                        text = "前往开启",
                        onClick = { openAccessibilitySettings(context) },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                SwitchRow(
                    title = "悬浮窗显示译文",
                    subtitle = if (hasOverlayPermission)
                        "划词后在浮窗中显示结果"
                    else "未授权；结果将显示在通知栏",
                    checked = hasOverlayPermission,
                    onCheckedChange = { enabled ->
                        if (enabled && !hasOverlayPermission) {
                            requestOverlayPermission(context)
                        }
                    },
                )

                // 悬浮窗权限缺失时提供跳转
                if (!hasOverlayPermission) {
                    PermissionHint(
                        text = "授予悬浮窗权限",
                        onClick = { requestOverlayPermission(context) },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                SwitchRow(
                    title = "静默自动翻译",
                    subtitle = "划词后直接翻译，不弹悬浮窗（结果写入通知栏）",
                    checked = settings.silentAutoTranslate,
                    onCheckedChange = viewModel::setSilentAutoTranslate,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                SwitchRow(
                    title = "悬浮窗自动消失",
                    subtitle = "翻译完成后自动关闭",
                    checked = settings.floatingAutoDismiss,
                    onCheckedChange = viewModel::setFloatingAutoDismiss,
                )
            }

            Spacer(Modifier.height(4.dp))

            // ============ 外观 ============
            SectionTitle("外观")

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("主题", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("system" to "跟随", "light" to "浅色", "dark" to "深色")
                            .forEach { (value, label) ->
                                ThemeChip(
                                    label = label,
                                    selected = settings.themeMode == value,
                                    onClick = { viewModel.setThemeMode(value) },
                                )
                            }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ============ 关于 ============
            SectionTitle("关于")

            // 品牌卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = Brand.APP_NAME_ZH,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = Brand.APP_NAME_EN,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = Brand.SLOGAN_ZH,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = Brand.SLOGAN_EN,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(14.dp))

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "开发者",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Text(
                        text = Brand.AUTHOR_FULL,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            SettingsCard {
                InfoRow("版本", Brand.VERSION)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                InfoRow(
                    label = "引擎",
                    value = EngineRegistry.all().joinToString(" / ") { it.displayName },
                )
            }

            Spacer(Modifier.height(10.dp))

            // 开源声明
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "开源声明",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = Brand.DERIVATION_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(Brand.UPSTREAM_URL),
                            )
                            context.startActivity(intent)
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            "查看 STranslate 原项目（MIT）→",
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------- 组件 ----------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionHint(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Text(text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EngineCard(
    engine: TranslateEngine,
    selected: Boolean,
    config: EngineConfig,
    onSelect: () -> Unit,
    onConfigChange: (EngineConfig) -> Unit,
) {
    var expanded by remember { mutableStateOf(selected && engine.requiresApiKey) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = engine.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold
                        else FontWeight.Normal,
                    )
                    Text(
                        text = if (engine.requiresApiKey) "需要配置 API Key"
                        else "开箱即用，无需配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (engine.requiresApiKey) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开配置",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (expanded && engine.requiresApiKey) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 自定义引擎：显示服务预设，一键填充
                if (engine.id == "custom") {
                    Text(
                        text = "快速预设",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PresetChips(
                        onPick = { preset ->
                            onConfigChange(
                                config.copy(
                                    baseUrl = preset.baseUrl,
                                    model = preset.defaultModel,
                                    apiPath = preset.apiPath,
                                    presetName = preset.name,
                                )
                            )
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                }

                ConfigField(
                    label = "服务地址 (baseUrl)",
                    value = config.baseUrl,
                    placeholder = if (engine.id == "custom") "https://open.bigmodel.cn/api/paas/v4"
                    else "https://api.deepseek.com",
                    onChange = { onConfigChange(config.copy(baseUrl = it)) },
                )
                Spacer(Modifier.height(10.dp))
                ConfigField(
                    label = "模型名称 (model)",
                    value = config.model,
                    placeholder = if (engine.id == "custom") "glm-4-flash" else "deepseek-chat",
                    onChange = { onConfigChange(config.copy(model = it)) },
                )
                Spacer(Modifier.height(10.dp))
                ConfigField(
                    label = "API Key",
                    value = config.apiKey,
                    placeholder = "sk-...",
                    isPassword = true,
                    onChange = { onConfigChange(config.copy(apiKey = it)) },
                )

                // 自定义引擎：高级选项
                if (engine.id == "custom") {
                    Spacer(Modifier.height(14.dp))
                    AdvancedSection(
                        config = config,
                        onConfigChange = onConfigChange,
                    )
                }

                Spacer(Modifier.height(10.dp))
                ConfigField(
                    label = "自定义提示词（可选）",
                    value = config.systemPrompt,
                    placeholder = "留空使用默认翻译提示词",
                    onChange = { onConfigChange(config.copy(systemPrompt = it)) },
                    singleLine = false,
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (engine.id == "custom")
                        "支持智谱 GLM / DeepSeek / 硅基流动 / Moonshot / Groq 等任意 OpenAI 兼容服务"
                    else "支持 DeepSeek / 硅基流动 / Groq / Ollama 等任意 OpenAI 兼容服务",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 12.sp) },
        placeholder = {
            Text(
                placeholder,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        shape = RoundedCornerShape(10.dp),
        singleLine = singleLine,
        visualTransformation = if (isPassword) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/** 自动翻译总览：把各条触发通道的状态一眼看清 */
@Composable
private fun AutoTranslateSummary(
    inputDebounce: Boolean,
    selection: Boolean,
    silent: Boolean,
    delayMs: Long,
    hasOverlay: Boolean,
    hasAccessibility: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "当前生效的翻译触发方式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AutoItem(
            index = "1",
            title = "输入框防抖",
            detail = if (inputDebounce) "停止输入 ${delayMs}ms 后自动翻译" else "已关闭",
            active = inputDebounce,
        )

        AutoItem(
            index = "2",
            title = "无障碍划词",
            detail = when {
                !selection -> "已关闭"
                !hasAccessibility -> "已开启，但系统未授权无障碍服务"
                silent -> "长按选中文字后静默翻译，结果在通知栏"
                !hasOverlay -> "长按选中文字后翻译；无悬浮窗权限，结果在通知栏"
                else -> "长按选中文字后弹悬浮窗显示译文"
            },
            active = selection && hasAccessibility,
        )

        if (selection && !hasAccessibility) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "提示：无障碍服务未启用，划词功能暂不生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun AutoItem(
    index: String,
    title: String,
    detail: String,
    active: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                fontSize = 11.sp,
                color = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        if (active) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 服务预设芯片：点击一键填充 baseUrl / model / path */
@Composable
private fun PresetChips(onPick: (EnginePreset) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CustomEnginePresets.all.forEach { preset ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onPick(preset) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = preset.name,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 自定义引擎的高级选项：请求路径 / 鉴权头 / 额外请求头 */
@Composable
private fun AdvancedSection(
    config: EngineConfig,
    onConfigChange: (EngineConfig) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "高级选项",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }

    if (expanded) {
        Spacer(Modifier.height(4.dp))

        ConfigField(
            label = "请求路径",
            value = config.apiPath,
            placeholder = "留空自动补 /v1/chat/completions",
            onChange = { onConfigChange(config.copy(apiPath = it)) },
        )
        Spacer(Modifier.height(10.dp))
        ConfigField(
            label = "鉴权头名称",
            value = config.authHeader,
            placeholder = "默认 Authorization",
            onChange = { onConfigChange(config.copy(authHeader = it)) },
        )
        Spacer(Modifier.height(10.dp))
        ConfigField(
            label = "鉴权前缀",
            value = config.authPrefix,
            placeholder = "默认 Bearer （含空格）",
            onChange = { onConfigChange(config.copy(authPrefix = it)) },
        )
        Spacer(Modifier.height(10.dp))
        ConfigField(
            label = "额外请求头",
            value = config.extraHeaders,
            placeholder = "每行一条，如 X-Api-Version: 1",
            onChange = { onConfigChange(config.copy(extraHeaders = it)) },
            singleLine = false,
        )
    }
}

// ---------- 系统设置跳转 ----------

private fun canDrawOverlays(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${dev.beyonddev.beyondlingo.service.SelectionAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
