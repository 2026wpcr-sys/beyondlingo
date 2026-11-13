package dev.beyonddev.beyondlingo.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.model.ModelDownloader
import dev.beyonddev.beyondlingo.data.model.ModelEntry
import dev.beyonddev.beyondlingo.data.model.ModelManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelUiState(
    val manifestUrl: String = "",
    val loading: Boolean = false,
    val entries: List<ModelEntry> = emptyList(),
    /** modelId -> 是否已就绪 */
    val ready: Map<String, Boolean> = emptyMap(),
    /** modelId -> 下载进度文本 */
    val progress: Map<String, String> = emptyMap(),
    val message: String? = null,
)

/**
 * 云空间模型管理：拉 manifest、展示可下载项、执行下载与校验。
 * 下载完成后由同传 ViewModel 读取 ModelDownloader.pathOf(...) 设置外部路径。
 */
class ModelManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val downloader = ModelDownloader(app)

    private val _state = MutableStateFlow(ModelUiState())
    val state: StateFlow<ModelUiState> = _state.asStateFlow()

    fun setManifestUrl(url: String) {
        _state.update { it.copy(manifestUrl = url) }
    }

    fun loadManifest() {
        val url = _state.value.manifestUrl.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(message = "请先填写 manifest 地址") }
            return
        }
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val result = downloader.fetchManifest(url)
            result.fold(
                onSuccess = { manifest ->
                    val ready = manifest.models.associate {
                        it.id to downloader.isReady(it)
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            entries = manifest.models,
                            ready = ready,
                            message = "找到 ${manifest.models.size} 个模型（v${manifest.version}）",
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(loading = false, message = "拉取失败：${t.message}")
                    }
                },
            )
        }
    }

    fun download(entry: ModelEntry) {
        _state.update {
            it.copy(progress = it.progress + (entry.id to "准备中…"))
        }
        viewModelScope.launch {
            downloader.download(entry).collect { p ->
                when (p) {
                    is ModelDownloader.Progress.Downloading -> {
                        val pct = if (p.totalBytes > 0) {
                            (p.downloadedBytes * 100 / p.totalBytes).toInt()
                        } else 0
                        _state.update {
                            it.copy(
                                progress = it.progress +
                                    (entry.id to "${p.fileKey} $pct%")
                            )
                        }
                    }

                    is ModelDownloader.Progress.Verifying ->
                        _state.update {
                            it.copy(progress = it.progress + (entry.id to "校验 SHA-256…"))
                        }

                    is ModelDownloader.Progress.Done -> {
                        _state.update {
                            it.copy(
                                progress = it.progress - entry.id,
                                ready = it.ready + (entry.id to true),
                                message = "${entry.name} 已就绪",
                            )
                        }
                    }

                    is ModelDownloader.Progress.Failed ->
                        _state.update {
                            it.copy(
                                progress = it.progress - entry.id,
                                message = "${entry.name} 失败：${p.message}",
                            )
                        }
                }
            }
        }
    }

    /** 供同传 ViewModel 使用 */
    fun pathOf(entry: ModelEntry, key: String): String? = downloader.pathOf(entry, key)

    fun readyAsrEntry(): ModelEntry? {
        val s = _state.value
        return s.entries.firstOrNull {
            it.kind == "asr" && s.ready[it.id] == true
        }
    }

    fun allEntries(): List<ModelEntry> = _state.value.entries
}
