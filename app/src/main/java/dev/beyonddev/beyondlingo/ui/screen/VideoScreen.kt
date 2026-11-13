package dev.beyonddev.beyondlingo.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.beyonddev.beyondlingo.vm.VideoViewModel

/**
 * 视频翻译页。
 *
 * 流程：选视频 → 抽音频 → 识别 → 翻译 → 生成双语 SRT
 * 播放：Media3 ExoPlayer + side-loaded 字幕（字幕文件由本页生成）。
 *
 * 双语字幕采用「同一条字幕内两行」的实现，不需要双字幕轨。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    viewModel: VideoViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) viewModel.setVideo(uri) }

    val exportSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri: Uri? -> if (uri != null) viewModel.exportSrt(uri) }

    // ExoPlayer：仅在已有视频时创建
    val player = remember(state.videoUri) {
        state.videoUri?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(it))
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    LaunchedEffect(state.cues.size) {
        if (state.cues.isNotEmpty()) {
            listState.animateScrollToItem((state.cues.size - 1).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频翻译") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.srtPath != null) {
                        TextButton2(onClick = { exportSrt.launch("translated.srt") })
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
            // 播放器
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickVideo.launch(arrayOf("video/*")) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text("选择视频") }

                Button(
                    onClick = viewModel::start,
                    enabled = state.videoUri != null && !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.running) "处理中" else "开始翻译") }
            }

            if (state.running || state.percent in 1..99) {
                Spacer(Modifier.height(12.dp))
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

            if (state.cues.isEmpty()) {
                Text(
                    "选择视频后点「开始翻译」。识别与翻译都在设备侧完成，视频不会被重新编码。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("双语字幕（${state.cues.size} 条）", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.cues, key = { it.index }) { cue ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(cue.original, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                cue.translated ?: if (cue.failed) "⚠ 翻译失败" else "…",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButton2(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text("导出 SRT") }
}
