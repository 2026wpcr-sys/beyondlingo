package dev.beyonddev.beyondlingo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
                text = "把模型放在你的云空间（HTTPS 直链 + 支持 Range），" +
                    "在此填入 manifest 地址。下载完成后同传会自动改用该模型。",
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
                                when {
                                    prog != null -> Text(prog, fontSize = 13.sp)
                                    ready -> Text("已就绪", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary)
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
