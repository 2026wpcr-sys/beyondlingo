package dev.beyonddev.beyondlingo.ui.screen

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beyonddev.beyondlingo.vm.DocumentViewModel

/**
 * 文档翻译页：选 txt/md/docx → 分块翻译 → 导出 md/txt。
 * 不支持 pdf（推迟到 MVP+1，见 wayfinder/document-parsing.md）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    viewModel: DocumentViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 取显示名，用于判断扩展名与展示
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull() ?: "document.txt"
            viewModel.pick(uri, name)
        }
    }

    val exportMd = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri: Uri? -> if (uri != null) viewModel.export(uri, markdown = true, bilingual = true) }

    val exportTxt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> if (uri != null) viewModel.export(uri, markdown = false, bilingual = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文档翻译") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.translatedCount > 0) {
                        TextButton(onClick = { exportMd.launch("translated.md") }) { Text("导出 md") }
                        TextButton(onClick = { exportTxt.launch("translated.txt") }) { Text("导出 txt") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickDoc.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*")) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text("选择文档") }

                Button(
                    onClick = viewModel::start,
                    enabled = state.blockCount > 0 && !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.running) "翻译中" else "开始翻译") }
            }

            if (state.fileName.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${state.fileName} · ${state.blockCount} 段 · 约 ${state.estimatedChars} 字符",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.running) {
                Spacer(Modifier.height(10.dp))
                Text("${state.stage}  ${state.percent}%", fontSize = 13.sp)
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(12.dp))

            if (state.preview.isEmpty()) {
                Text(
                    "支持 txt / md / docx。docx 使用本地自研解析（不依赖 POI），" +
                        "仅支持正文、标题、列表、表格；不支持 .doc 旧格式与扫描版 PDF。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("译文预览（前 ${state.preview.size} 段）", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(state.preview) { _, pair ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(pair.first, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(pair.second ?: "…", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
