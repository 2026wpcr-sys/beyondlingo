package dev.beyonddev.beyondlingo.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.model.BuiltInModels
import dev.beyonddev.beyondlingo.data.model.ModelDownloader
import dev.beyonddev.beyondlingo.data.model.ModelEntry
import dev.beyonddev.beyondlingo.data.model.ModelManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelUiState(
    /**
     * manifest 地址。留空 = 用 App 内置清单（多档 Whisper 可选）。
     *
     * 需要本地联调时手填地址即可，例如：
     * `http://127.0.0.1:8000/manifest.json`（配合 `adb reverse tcp:8000 tcp:8000`）。
     * 不再在 debug 里预填，避免掩盖内置清单的真实行为。
     */
    val manifestUrl: String = "",
    val loading: Boolean = false,
    val entries: List<ModelEntry> = emptyList(),
    /** modelId -> 是否已就绪 */
    val ready: Map<String, Boolean> = emptyMap(),
    /** modelId -> 下载进度文本 */
    val progress: Map<String, String> = emptyMap(),
    val message: String? = null,
    /** 当前清单来源：内置 或 用户填的地址 */
    val usingBuiltIn: Boolean = true,
    /** 用户选定的 ASR 模型 id（空 = 未显式选择） */
    val selectedAsrId: String = "",
    /** 云端 ASR（Groq）API Key */
    val cloudAsrKey: String = "",
    /** Azure TTS API Key（神经语音朗读） */
    val azureTtsKey: String = "",
    /** Azure TTS 区域 */
    val azureTtsRegion: String = "eastus",
    /** 云端 TTS 服务商：local / azure / openai / edge（Edge 无需 Key、无额外字段） */
    val ttsProvider: String = "local",
    val ttsBaseUrl: String = "",
    val ttsPath: String = "/audio/speech",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
    val ttsApiKey: String = "",
    /**
     * ASR 卡片当前展开的分支：local / cloud。
     *
     * 必须与「已选模型」分开：点「本地」只是切视图，不能因为本地模型还没下载
     * 就拒绝（真机 bug：从云端点回本地毫无反应）。
     */
    val asrTab: String = "local",
)

/**
 * 云空间模型管理：拉 manifest、展示可下载项、执行下载与校验。
 * 下载完成后由同传 ViewModel 读取 ModelDownloader.pathOf(...) 设置外部路径。
 */
class ModelManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val downloader = ModelDownloader(app)

    private val settingsStore = dev.beyonddev.beyondlingo.data.SettingsStore(app)

    /**
     * 把某个已就绪的 ASR 模型设为当前使用。
     *
     * 只改设置，不重新加载 —— 下次进入对话翻译时按新选择初始化。
     */
    fun selectAsrModel(entry: ModelEntry) {
        if (entry.kind != "asr-offline" && entry.kind != "asr-cloud") return
        if (_state.value.ready[entry.id] != true) return
        viewModelScope.launch {
            runCatching { settingsStore.setAsrModelId(entry.id) }
            _state.update { it.copy(selectedAsrId = entry.id) }
        }
    }

    /** 保存云端 ASR（Groq）的 API Key */
    fun setCloudAsrKey(key: String) {
        _state.update { it.copy(cloudAsrKey = key) }
        viewModelScope.launch {
            runCatching { settingsStore.setCloudAsrApiKey(key.trim()) }
        }
    }

    /** 保存 Azure TTS 配置（神经语音朗读） */
    fun setAzureTts(key: String, region: String) {
        _state.update { it.copy(azureTtsKey = key, azureTtsRegion = region) }
        viewModelScope.launch {
            runCatching { settingsStore.setAzureTts(key.trim(), region.trim()) }
        }
    }

    /** 保存通用云端 TTS 配置（provider + OpenAI 兼容字段；Edge 只用 provider） */
    fun setCloudTts(
        provider: String = _state.value.ttsProvider,
        apiKey: String = _state.value.ttsApiKey,
        baseUrl: String = _state.value.ttsBaseUrl,
        path: String = _state.value.ttsPath,
        model: String = _state.value.ttsModel,
        voice: String = _state.value.ttsVoice,
    ) {
        _state.update {
            it.copy(
                ttsProvider = provider,
                ttsApiKey = apiKey,
                ttsBaseUrl = baseUrl,
                ttsPath = path,
                ttsModel = model,
                ttsVoice = voice,
            )
        }
        viewModelScope.launch {
            runCatching {
                settingsStore.setCloudTts(
                    provider = provider,
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.trim(),
                    path = path.trim(),
                    model = model.trim(),
                    voice = voice.trim(),
                )
            }
        }
    }

    /** 切换 ASR 卡片分支（仅视图状态，不改已选模型） */
    fun setAsrTab(tab: String) {
        _state.update { it.copy(asrTab = tab) }
    }

    private val _state = MutableStateFlow(ModelUiState())
    val state: StateFlow<ModelUiState> = _state.asStateFlow()

    init {
        // 读一次用户选定的模型，用于在列表里标「使用中」。
        // 必须放在 _state 之后：Kotlin 按声明顺序初始化，init 里提前访问会拿到 null。
        viewModelScope.launch {
            val s = runCatching { settingsStore.settings.first() }.getOrNull()
            _state.update {
                it.copy(
                    selectedAsrId = s?.asrModelId.orEmpty(),
                    cloudAsrKey = s?.cloudAsrApiKey.orEmpty(),
                    azureTtsKey = s?.azureTtsKey.orEmpty(),
                    azureTtsRegion = s?.azureTtsRegion.orEmpty().ifBlank { "eastus" },
                    ttsProvider = s?.ttsProvider ?: "local",
                    ttsBaseUrl = s?.ttsBaseUrl.orEmpty(),
                    ttsPath = s?.ttsPath ?: "/audio/speech",
                    ttsModel = s?.ttsModel ?: "tts-1",
                    ttsVoice = s?.ttsVoice ?: "alloy",
                    ttsApiKey = s?.ttsApiKey.orEmpty(),
                    // 已选云端就展开云端分支，否则本地
                    asrTab = if (s?.asrModelId?.startsWith("groq") == true) "cloud" else "local",
                )
            }
        }
        // 首次进页面就加载清单：否则 entries 为空，卡片里一个模型芯片都渲染不出来
        // （真机踩过：重装后模型页只剩服务商按钮，看不到 base/small）。
        loadManifest()
    }

    fun setManifestUrl(url: String) {
        _state.update { it.copy(manifestUrl = url) }
    }

    /**
     * 加载清单。
     *
     * 地址为空 → 用 App 内置清单（BuiltInModels），不发网络请求；
     * 地址非空 → 走用户填的云空间地址。
     */
    fun loadManifest() {
        val url = _state.value.manifestUrl.trim()
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val result = if (url.isEmpty()) {
                runCatching { BuiltInModels.manifest() }
            } else {
                downloader.fetchManifest(url)
            }
            result.fold(
                onSuccess = { manifest ->
                    val ready = manifest.models.associate {
                        // 云端档没有文件要下载，始终「就绪」；是否可用取决于 API Key
                        it.id to if (it.kind == "asr-cloud") true else downloader.isReady(it)
                    }
                    val builtIn = url.isEmpty()
                    _state.update {
                        it.copy(
                            loading = false,
                            entries = manifest.models,
                            ready = ready,
                            usingBuiltIn = builtIn,
                            message = if (builtIn) {
                                "内置清单：${manifest.models.size} 个模型（v${manifest.version}）"
                            } else {
                                "找到 ${manifest.models.size} 个模型（v${manifest.version}）"
                            },
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(loading = false, message = "拉取失败：${describe(t)}")
                    }
                },
            )
        }
    }

    /** 异常 message 常为 null（如 JSONException），要给出可读兜底 */
    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() }
            ?: (t.cause?.message?.takeIf { it.isNotBlank() })
            ?: t.javaClass.simpleName

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
