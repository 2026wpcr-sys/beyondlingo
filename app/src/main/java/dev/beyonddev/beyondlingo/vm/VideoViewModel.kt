package dev.beyonddev.beyondlingo.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.SettingsStore
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.EngineRegistry
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.data.video.Cue
import dev.beyonddev.beyondlingo.data.video.Srt
import dev.beyonddev.beyondlingo.data.video.VideoTranslator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class VideoUiState(
    val videoUri: Uri? = null,
    val running: Boolean = false,
    val stage: String = "",
    val percent: Int = 0,
    val cues: List<Cue> = emptyList(),
    val srtPath: String? = null,
    val error: String? = null,
)

class VideoViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val translator = VideoTranslator(app)

    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    fun setVideo(uri: Uri) {
        if (_state.value.running) return
        _state.update {
            it.copy(videoUri = uri, cues = emptyList(), srtPath = null, error = null)
        }
    }

    fun start() {
        val uri = _state.value.videoUri ?: run {
            _state.update { it.copy(error = "请先选择视频") }
            return
        }
        if (_state.value.running) return

        _state.update { it.copy(running = true, error = null, percent = 0, stage = "准备…") }
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            val target = if (s.foreignLang == LangEnum.Auto) LangEnum.English else s.foreignLang
            val engineId = EngineRegistry.DEFAULT_ENGINE_ID
            val config: EngineConfig = s.configFor(engineId)

            val result = translator.translate(
                videoUri = uri,
                targetLang = target,
                engineId = engineId,
                engineConfig = config,
            ) { stage, pct ->
                _state.update { it.copy(stage = stage, percent = pct) }
            }

            result.fold(
                onSuccess = { r ->
                    _state.update {
                        it.copy(
                            running = false,
                            stage = "完成",
                            percent = 100,
                            cues = r.cues,
                            srtPath = r.srtFile?.absolutePath,
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            running = false,
                            stage = "",
                            error = t.message ?: t.javaClass.simpleName,
                        )
                    }
                },
            )
        }
    }

    /** 导出 SRT 到用户选择的位置 */
    fun exportSrt(target: Uri) {
        val path = _state.value.srtPath ?: return
        viewModelScope.launch {
            runCatching {
                val bytes = File(path).readBytes()
                getApplication<Application>().contentResolver
                    .openOutputStream(target)?.use { it.write(bytes) }
            }.onFailure { t ->
                _state.update { it.copy(error = "导出失败：${t.message}") }
            }
        }
    }
}
