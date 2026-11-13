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
            Text(
                text = "对话翻译需要先下载一个语音模型。体积越大精度越高，按需选择：" +
                    "tiny 极速 / base 均衡 / small 高精度 / turbo 最高。" +
                    "下载后点「设为当前」切换。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.manifestUrl,
                onValueChange = viewModel::setManifestUrl,
                label = { Text("manifest.json 地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::loadManifest,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.loading) "加载中…" else "拉取模型列表")
            }

            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.entries, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(entry.name, fontSize = 16.sp)
                            Text(
                                "${entry.kind} · ${entry.files.size} 个文件",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val ready = state.ready[entry.id] == true
                                val prog = state.progress[entry.id]
                                val isActive = ready && entry.kind == "asr-offline" &&
                                    (state.selectedAsrId == entry.id ||
                                        (state.selectedAsrId.isEmpty() &&
                                            entry.id == "whisper-base-int8"))
                                when {
                                    prog != null -> Text(prog, fontSize = 13.sp)
                                    ready -> {
                                        Text(
                                            "已就绪",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (entry.kind == "asr-offline") {
                                            Spacer(Modifier.width(12.dp))
                                            if (isActive) {
                                                Text(
                                                    "使用中",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            } else {
                                                TextButton(
                                                    onClick = { viewModel.selectAsrModel(entry) },
                                                    contentPadding = PaddingValues(
                                                        horizontal = 8.dp, vertical = 0.dp
                                                    ),
                                                ) { Text("设为当前", fontSize = 13.sp) }
                                            }
                                        }
                                    }
                                    else -> TextButton(onClick = { viewModel.download(entry) }) {
                                        Text("下载")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
