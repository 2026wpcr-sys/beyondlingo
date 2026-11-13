package dev.beyonddev.beyondlingo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beyonddev.beyondlingo.vm.ModelManagerViewModel

/**
 * 云空间模型管理：填 manifest 地址 → 拉列表 → 下载/校验 → 就绪后同传自动使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    viewModel: ModelManagerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            Spacer(Modifier.height(12.dp))

            // 清单在 ViewModel init 里自动加载，页面上不再暴露地址输入和拉取按钮。
            state.message?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }

            // ---- 朗读（TTS）配置 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("朗读语音（TTS）", fontSize = 16.sp)
                    Text(
                        "选「本地」不花钱、离线；选云端音质接近真人。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    // 服务商选择
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "local" to "本地",
                            "azure" to "Azure",
                            "openai" to "OpenAI兼容",
                            "edge" to "Edge（免费）",
                        ).forEach { (id, label) ->
                            val active = state.ttsProvider == id
                            TextButton(
                                onClick = { viewModel.setCloudTts(provider = id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }

                    when (state.ttsProvider) {
                        "azure" -> {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.azureTtsKey,
                                onValueChange = { viewModel.setAzureTts(it, state.azureTtsRegion) },
                                label = { Text("Azure Speech Key") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.azureTtsRegion,
                                onValueChange = { viewModel.setAzureTts(state.azureTtsKey, it) },
                                label = { Text("区域（如 eastus / southcentralus）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        "edge" -> {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "微软 Edge 朗读：免费，无需 Key、无需注册，按语言自动选音色 " +
                                    "（中文：晓晓；西班牙语：Elvira；英语：Aria）。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "注意：音频会发送到微软服务器合成（与 Groq 云端识别一样，" +
                                    "不是纯本地）。Edge 接口未公开，微软改校验时可能暂时不可用，" +
                                    "失败会自动回退本地朗读。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        "openai" -> {
                            Spacer(Modifier.height(6.dp))
                            // 常用服务一键填充
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                dev.beyonddev.beyondlingo.data.asr.CloudTtsEngine.Presets.all
                                    .forEach { p ->
                                        TextButton(
                                            onClick = {
                                                viewModel.setCloudTts(
                                                    baseUrl = p.baseUrl,
                                                    path = p.path,
                                                    model = p.model,
                                                    voice = p.voice,
                                                )
                                            },
                                            contentPadding = PaddingValues(
                                                horizontal = 10.dp, vertical = 0.dp
                                            ),
                                        ) { Text(p.name, fontSize = 13.sp) }
                                    }
                            }
                            OutlinedTextField(
                                value = state.ttsBaseUrl,
                                onValueChange = { viewModel.setCloudTts(baseUrl = it) },
                                label = { Text("Base URL（如 https://api.openai.com/v1）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.ttsApiKey,
                                onValueChange = { viewModel.setCloudTts(apiKey = it) },
                                label = { Text("API Key") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.ttsModel,
                                onValueChange = { viewModel.setCloudTts(model = it) },
                                label = { Text("模型（如 tts-1）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.ttsVoice,
                                onValueChange = { viewModel.setCloudTts(voice = it) },
                                label = { Text("音色（如 alloy / nova）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.ttsPath,
                                onValueChange = { viewModel.setCloudTts(path = it) },
                                label = { Text("路径（默认 /audio/speech）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "任何兼容 OpenAI /audio/speech 的服务都可用（如 OpenAI）。" +
                                    "需返回 wav 格式。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "使用本地语音，无需配置。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- 语音识别（ASR）配置：与 TTS 一致的固定卡片 ----
            val localEntries = state.entries.filter { it.kind == "asr-offline" }
            val cloudEntry = state.entries.firstOrNull { it.kind == "asr-cloud" }
            // 分支由 asrTab 决定（纯视图状态），与「已选模型」解耦：
            // 否则本地模型没下载时点「本地」会被 selectAsrModel 的 ready 检查拒绝，
            // 表现为「点本地没反应」。
            val cloudSelected = state.asrTab == "cloud"

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("语音识别（ASR）", fontSize = 16.sp)
                    Text(
                        "本地不出设备、可离线；云端精度最高，但音频会上传。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    // 服务商：本地 / Groq 云端
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val localActive = !cloudSelected
                        TextButton(
                            onClick = {
                                // 只切视图；真正选中由下面的模型芯片负责
                                viewModel.setAsrTab("local")
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text(
                                "本地",
                                fontSize = 13.sp,
                                fontWeight = if (localActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (localActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(
                            onClick = {
                                viewModel.setAsrTab("cloud")
                                // 云端档无文件可下，直接选中（没填 Key 时启动会明确报错）
                                cloudEntry?.let { viewModel.selectAsrModel(it) }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text(
                                "Groq 云端",
                                fontSize = 13.sp,
                                fontWeight = if (cloudSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (cloudSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    if (cloudSelected) {
                        // ---- 云端：Key + 模型档 ----
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = state.cloudAsrKey,
                            onValueChange = viewModel::setCloudAsrKey,
                            label = { Text("Groq API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "⚠ 音频会上传到 Groq 服务器识别，不再只在设备本地处理。需要联网。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "模型：whisper-large-v3（最准，WER 10.3%）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // ---- 本地：两档模型芯片 ----
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            localEntries.forEach { entry ->
                                val ready = state.ready[entry.id] == true
                                val prog = state.progress[entry.id]
                                val active = state.selectedAsrId == entry.id ||
                                    (state.selectedAsrId.isEmpty() &&
                                        entry.id == "whisper-base-int8")
                                val shortName = when (entry.id) {
                                    "whisper-base-int8" -> "base 均衡"
                                    "whisper-small-int8" -> "small 高精度"
                                    else -> entry.name
                                }
                                TextButton(
                                    onClick = {
                                        if (ready) {
                                            viewModel.selectAsrModel(entry)
                                        } else if (prog == null) {
                                            viewModel.download(entry)
                                        }
                                    },
                                    contentPadding = PaddingValues(
                                        horizontal = 10.dp, vertical = 0.dp
                                    ),
                                ) {
                                    Text(
                                        if (ready) shortName else "$shortName · 下载",
                                        fontSize = 13.sp,
                                        fontWeight = if (active) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                        color = when {
                                            prog != null -> MaterialTheme.colorScheme.onSurfaceVariant
                                            active -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                        // 状态行：进度 / 已就绪 / 使用中
                        val cur = localEntries.firstOrNull {
                            state.selectedAsrId == it.id ||
                                (state.selectedAsrId.isEmpty() &&
                                    it.id == "whisper-base-int8")
                        } ?: localEntries.firstOrNull()
                        if (cur != null) {
                            val prog = state.progress[cur.id]
                            val ready = state.ready[cur.id] == true
                            Spacer(Modifier.height(2.dp))
                            Text(
                                when {
                                    prog != null -> prog
                                    ready -> "已就绪 · 使用中"
                                    else -> "未下载，点上方模型开始下载"
                                },
                                fontSize = 11.sp,
                                color = if (ready) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
