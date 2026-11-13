package dev.beyonddev.beyondlingo.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.SettingsStore
import dev.beyonddev.beyondlingo.data.document.Block
import dev.beyonddev.beyondlingo.data.document.DocumentTranslator
import dev.beyonddev.beyondlingo.data.engine.EngineRegistry
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DocumentUiState(
    val fileName: String = "",
    val running: Boolean = false,
    val stage: String = "",
    val percent: Int = 0,
    val blockCount: Int = 0,
    val translatedCount: Int = 0,
    val estimatedChars: Int = 0,
    val exportedPath: String? = null,
    /** 预览：原文/译文成对 */
    val preview: List<Pair<String, String?>> = emptyList(),
    val error: String? = null,
)

class DocumentViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val translator = DocumentTranslator(app)

    private var blocks: List<Block> = emptyList()
    private var translations: Map<Int, String> = emptyMap()
    private var workFile: File? = null

    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()

    /** 选中文档后先落到私有目录并估算体量 */
    fun pick(uri: Uri, displayName: String) {
        if (_state.value.running) return
        viewModelScope.launch {
            runCatching {
                val dir = File(getApplication<Application>().cacheDir, "doc-translate").apply { mkdirs() }
                val f = File(dir, "input.${displayName.substringAfterLast('.', "txt")}")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                } ?: error("无法读取文档")

                workFile = f
                val parsed = dev.beyonddev.beyondlingo.data.document.DocumentParser.parse(f, displayName)
                blocks = parsed
                val est = translator.estimate(parsed)

                _state.update {
                    it.copy(
                        fileName = displayName,
                        blockCount = parsed.size,
                        estimatedChars = est,
                        error = null,
                        exportedPath = null,
                    )
                }
            }.onFailure { t ->
                _state.update { it.copy(error = "读取失败：${t.message}") }
            }
        }
    }

    fun start() {
        val file = workFile ?: run {
            _state.update { it.copy(error = "请先选择文档") }
            return
        }
        if (_state.value.running) return

        _state.update { it.copy(running = true, error = null, percent = 0, stage = "准备…") }
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            val target = if (s.foreignLang == LangEnum.Auto) LangEnum.English else s.foreignLang
            val engineId = EngineRegistry.DEFAULT_ENGINE_ID

            val result = translator.translate(
                file = file,
                displayName = _state.value.fileName,
                targetLang = target,
                engineId = engineId,
                engineConfig = s.configFor(engineId),
            ) { stage, pct ->
                _state.update { it.copy(stage = stage, percent = pct) }
            }

            result.fold(
                onSuccess = { r ->
                    translations = r.translations
                    val preview = r.blocks.take(40).map {
                        it.text to r.translations[it.id]
                    }
                    _state.update {
                        it.copy(
                            running = false,
                            stage = "完成",
                            percent = 100,
                            translatedCount = r.translations.size,
                            preview = preview,
                            error = if (r.failedCount > 0) "${r.failedCount} 个段落翻译失败" else null,
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(running = false, stage = "", error = t.message ?: t.javaClass.simpleName)
                    }
                },
            )
        }
    }

    fun export(target: Uri, markdown: Boolean, bilingual: Boolean) {
        viewModelScope.launch {
            runCatching {
                val text = if (markdown) {
                    translator.renderMarkdown(blocks, translations, bilingual)
                } else {
                    translator.renderPlain(blocks, translations, bilingual)
                }
                getApplication<Application>().contentResolver
                    .openOutputStream(target)?.use { it.write(text.toByteArray()) }
                    ?: error("无法写入目标文件")
            }.onSuccess {
                _state.update { it.copy(exportedPath = target.toString()) }
            }.onFailure { t ->
                _state.update { it.copy(error = "导出失败：${t.message}") }
            }
        }
    }
}
