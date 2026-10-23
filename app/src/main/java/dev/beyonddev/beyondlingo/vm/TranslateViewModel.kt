package dev.beyonddev.beyondlingo.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.AppSettings
import dev.beyonddev.beyondlingo.data.SettingsStore
import dev.beyonddev.beyondlingo.data.TranslateRepository
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.EngineRegistry
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.data.engine.TranslateEngine
import dev.beyonddev.beyondlingo.data.engine.TranslateOutcome
import dev.beyonddev.beyondlingo.util.Debouncer
import dev.beyonddev.beyondlingo.util.LanguageDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 主翻译页 UI 状态 */
sealed interface TranslateUiState {
    data object Idle : TranslateUiState
    data class Ready(val input: String) : TranslateUiState
    data class Translating(val input: String) : TranslateUiState
    data class Success(
        val input: String,
        val output: String,
        val elapsedMs: Long,
        val detectedLang: LangEnum?,
        val engineName: String,
        /** 是否由自动翻译触发（用于 UI 提示） */
        val autoTriggered: Boolean = false,
    ) : TranslateUiState
    data class Error(
        val input: String,
        val message: String,
        val retryable: Boolean,
    ) : TranslateUiState
}

/** 自动翻译的运行状态，用于设置页展示 */
data class AutoTranslateStatus(
    val inputDebounceEnabled: Boolean,
    val silentEnabled: Boolean,
    val lastAutoAt: Long = 0L,
)

class TranslateViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TranslateRepository(app)
    private val settingsStore = SettingsStore(app)
    private val debouncer = Debouncer(viewModelScope)

    private val _uiState = MutableStateFlow<TranslateUiState>(TranslateUiState.Idle)
    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _engine = MutableStateFlow<TranslateEngine>(EngineRegistry.get(null))
    val engine: StateFlow<TranslateEngine> = _engine.asStateFlow()

    private val _autoStatus = MutableStateFlow(AutoTranslateStatus(false, false))
    val autoStatus: StateFlow<AutoTranslateStatus> = _autoStatus.asStateFlow()

    /** 当前操作序号，用于并发隔离（参照 STranslate 的 TranslationOperation） */
    private var operationCounter = 0L
    private var translateJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                _settings.value = s
                _engine.value = EngineRegistry.get(s.engineId)
                _autoStatus.value = AutoTranslateStatus(
                    inputDebounceEnabled = s.autoTranslate && s.autoTranslateDelayMs > 0,
                    silentEnabled = s.silentAutoTranslate,
                    lastAutoAt = _autoStatus.value.lastAutoAt,
                )
            }
        }
    }

    // ---------- 输入 ----------

    fun onInputChanged(text: String) {
        _input.value = text
        val s = _settings.value

        if (text.isBlank()) {
            debouncer.cancel()
            translateJob?.cancel()
            _uiState.value = TranslateUiState.Idle
            return
        }

        if (_uiState.value !is TranslateUiState.Translating) {
            _uiState.value = TranslateUiState.Ready(text)
        }

        // A. 输入防抖自动翻译
        if (s.autoTranslate) {
            debouncer.schedule(s.autoTranslateDelayMs) {
                doTranslate(text, checkCacheFirst = true, auto = true)
            }
        }
    }

    fun clear() {
        debouncer.cancel()
        translateJob?.cancel()
        _input.value = ""
        _uiState.value = TranslateUiState.Idle
    }

    // ---------- 翻译 ----------

    /** 手动点「翻译」/「重试」：跳过缓存，强制请求 */
    fun translateNow() {
        val text = _input.value
        if (text.isBlank()) return
        debouncer.cancel()
        translateJob = viewModelScope.launch {
            doTranslate(text, checkCacheFirst = false, auto = false)
        }
    }

    /**
     * C. 外部文本静默翻译。
     *
     * 用于划词/剪贴板场景：直接翻译并更新结果区，不弹悬浮窗。
     * 由悬浮窗服务在 silent 模式下调用，或主界面接收外部文本时调用。
     */
    fun translateSilently(text: String) {
        if (text.isBlank()) return
        _input.value = text
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            doTranslate(text, checkCacheFirst = true, auto = true)
            _autoStatus.value = _autoStatus.value.copy(lastAutoAt = System.currentTimeMillis())
        }
    }

    private suspend fun doTranslate(text: String, checkCacheFirst: Boolean, auto: Boolean) {
        val opId = ++operationCounter
        _uiState.value = TranslateUiState.Translating(text)

        val s = _settings.value
        val engine = EngineRegistry.get(s.engineId)
        val config = s.configFor(s.engineId)

        // 源语言为自动检测时，用本地启发式先判一次，避免引擎识别不准
        val source = if (s.defaultSourceLang == LangEnum.Auto) {
            LanguageDetector.detect(text)
        } else {
            s.defaultSourceLang
        }

        val started = System.currentTimeMillis()

        // 任何异常都必须转为 Error 状态，绝不能让协程崩溃
        val outcome = try {
            repo.translate(
                text = text,
                source = source,
                target = s.defaultTargetLang,
                engineId = s.engineId,
                config = config,
                checkCacheFirst = checkCacheFirst,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            TranslateOutcome.Failure(
                message = t.message ?: "翻译失败，请重试",
                cause = t,
                retryable = true,
            )
        }

        // 并发隔离：被新请求取代则丢弃
        if (opId != operationCounter) return

        _uiState.value = when (outcome) {
            is TranslateOutcome.Success -> TranslateUiState.Success(
                input = text,
                output = outcome.text,
                elapsedMs = if (outcome.elapsedMs > 0) outcome.elapsedMs
                else System.currentTimeMillis() - started,
                detectedLang = outcome.detectedSource ?: source.takeIf { it != LangEnum.Auto },
                engineName = engine.displayName,
                autoTriggered = auto,
            )

            is TranslateOutcome.Failure -> TranslateUiState.Error(
                input = text,
                message = outcome.message,
                retryable = outcome.retryable,
            )
        }
    }

    // ---------- 语言 ----------

    fun setSourceLang(lang: LangEnum) {
        viewModelScope.launch { settingsStore.setSourceLang(lang) }
        retranslateIfPossible()
    }

    fun setTargetLang(lang: LangEnum) {
        viewModelScope.launch { settingsStore.setTargetLang(lang) }
        retranslateIfPossible()
    }

    fun swapLanguages() {
        viewModelScope.launch {
            val s = _settings.value
            val from = s.defaultSourceLang
            val to = s.defaultTargetLang
            if (from == LangEnum.Auto) return@launch
            settingsStore.setSourceLang(to)
            settingsStore.setTargetLang(from)
        }
        retranslateIfPossible()
    }

    fun setEngine(engineId: String) {
        viewModelScope.launch { settingsStore.setEngineId(engineId) }
        retranslateIfPossible()
    }

    private fun retranslateIfPossible() {
        val text = _input.value
        if (text.isBlank()) return
        translateJob = viewModelScope.launch {
            doTranslate(text, checkCacheFirst = false, auto = false)
        }
    }

    // ---------- 设置透传 ----------

    fun setAutoTranslate(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoTranslate(enabled) }
    }

    fun setAutoTranslateDelay(ms: Long) {
        viewModelScope.launch { settingsStore.setAutoTranslateDelay(ms) }
    }

    fun setCopyAfterTranslate(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setCopyAfterTranslate(enabled) }
    }

    fun saveEngineConfig(engineId: String, config: EngineConfig) {
        viewModelScope.launch { settingsStore.setEngineConfig(engineId, config) }
    }

    fun setSelectionTranslate(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setSelectionTranslate(enabled) }
    }

    /** C. 静默自动翻译开关 */
    fun setSilentAutoTranslate(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setSilentAutoTranslate(enabled) }
    }

    fun setFloatingAutoDismiss(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setFloatingAutoDismiss(enabled) }
    }

    fun setHistoryLimit(limit: Int) {
        viewModelScope.launch { settingsStore.setHistoryLimit(limit) }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    suspend fun currentSettings(): AppSettings = settingsStore.settings.first()
}
