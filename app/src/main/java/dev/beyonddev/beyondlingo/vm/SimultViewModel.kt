package dev.beyonddev.beyondlingo.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.SettingsStore
import dev.beyonddev.beyondlingo.data.SimultOverlayBus
import dev.beyonddev.beyondlingo.data.TranslateRepository
import dev.beyonddev.beyondlingo.data.asr.AsrEngine
import dev.beyonddev.beyondlingo.data.asr.AsrUpdate
import dev.beyonddev.beyondlingo.data.asr.AudioRecorder
import dev.beyonddev.beyondlingo.data.asr.BidirectionalResolver
import dev.beyonddev.beyondlingo.data.asr.BidirectionalSession
import dev.beyonddev.beyondlingo.data.asr.PunctuationEngine
import dev.beyonddev.beyondlingo.data.asr.TtsEngine
import dev.beyonddev.beyondlingo.data.asr.WavUtil
import dev.beyonddev.beyondlingo.data.asr.WhisperEngine
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.EngineRegistry
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.data.engine.TranslateOutcome
import dev.beyonddev.beyondlingo.service.SimultForegroundService
import dev.beyonddev.beyondlingo.service.SimultOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一条字幕。原文先出，译文异步补齐。
 *
 * 对应翻译鸥的 `emitPendingFinalSegment` 语义：
 * final 到达时翻译还没回来，先挂一条 translating=true 的记录，
 * 翻译返回后原地替换 —— 而不是「原文先上屏、译文再追加一行」。
 */
data class SubtitleLine(
    val segmentId: Int,
    val original: String,
    val translated: String? = null,
    val translating: Boolean = true,
    val failed: Boolean = false,
    /** 双向模式下这句是谁说的；单向模式恒为 UNKNOWN */
    val side: BidirectionalResolver.Side = BidirectionalResolver.Side.UNKNOWN,
    /** 方向来源文案，如 "中文 → 西班牙语" */
    val directionLabel: String = "",
)

data class SimultUiState(
    val running: Boolean = false,
    val loading: Boolean = false,
    /** 当前正在说的临时字幕（会被覆盖） */
    val partial: String = "",
    /** 已确认的字幕段 */
    val lines: List<SubtitleLine> = emptyList(),
    val error: String? = null,
    /** 双向模式（Whisper + VAD），false 表示单向流式 */
    val bidirectional: Boolean = false,
    /** VAD 判定当前正在说话 */
    val speaking: Boolean = false,
    /** 双向模式的左侧（我方）语言 */
    val leftLang: LangEnum = LangEnum.ChineseSimplified,
    /** 双向模式的右侧（对方）语言 */
    val rightLang: LangEnum = LangEnum.Spanish,
)

/**
 * 同声传译 ViewModel。
 *
 * 链路：AudioRecord → sherpa-onnx 流式识别 → 端点判定 → 翻译 → 双语字幕。
 *
 * 关键设计（参考翻译鸥）：
 * 1. partial 高频更新、final 低频更新，UI 只按 segmentId 定位，不做整表重排
 * 2. 翻译异步，失败不阻塞下一段识别
 * 3. 录音线程只做搬运 + 喂模型，翻译在 viewModelScope
 */
class SimultViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = TranslateRepository(app)
    private val engine = AsrEngine(app)
    private val punctuation = PunctuationEngine(app)
    private val tts = TtsEngine(app)
    private val whisper = WhisperEngine(app)
    private val resolver = BidirectionalResolver()
    private var bidiSession: BidirectionalSession? = null
    private val downloader = dev.beyonddev.beyondlingo.data.model.ModelDownloader(app)

    private var recorder: AudioRecorder? = null

    private val _state = MutableStateFlow(SimultUiState())
    val state: StateFlow<SimultUiState> = _state.asStateFlow()

    /**
     * 同传译文的目标语言。
     *
     * 关键点：ASR 识别结果是本地语音的文字（中文场景即中文）。
     * 若沿用主页的 defaultTargetLang（默认简体中文），等于「中文→中文」不翻译。
     * 所以同传默认译成用户的「外语」（homeLang/foreignLang 里的 foreignLang），
     * 同时把引擎固定为免配置的 Yandex，保证开箱可用、不受主页 GLM 未配 Key 影响。
     */
    private var targetLang: LangEnum = LangEnum.English
    private var engineId: String = EngineRegistry.DEFAULT_ENGINE_ID
    private var engineConfig: EngineConfig = EngineConfig.EMPTY

    fun start() {
        if (_state.value.running || _state.value.loading) return

        refreshExternalModel()
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // 标点 / TTS 模型可选：失败不影响主链路
            withContext(Dispatchers.IO) {
                punctuation.initialize()
                tts.initialize()
            }
            val ok = withContext(Dispatchers.IO) { engine.initialize() }
            if (!ok) {
                _state.update { it.copy(loading = false, error = "语音模型加载失败") }
                return@launch
            }

            val s = settingsStore.settings.first()
            // 同传固定用免配置引擎；目标语言取用户的「外语」
            targetLang = if (s.foreignLang == LangEnum.Auto) LangEnum.English else s.foreignLang
            engineId = EngineRegistry.DEFAULT_ENGINE_ID
            engineConfig = s.configFor(engineId)

            val r = AudioRecorder(
                onSamples = { samples ->
                    // 录音线程：只喂模型 + 产出更新
                    val upd = engine.accept(samples)
                    if (upd != null) handleUpdate(upd)
                },
                onError = { msg ->
                    _state.update { it.copy(error = msg) }
                },
            )
            recorder = r
            r.start()
            startForegroundService()
            _state.update { it.copy(loading = false, running = true) }
        }
    }

    private fun startForegroundService() {
        val ctx: Context = getApplication()
        val intent = Intent(ctx, SimultForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun stopForegroundService() {
        val ctx: Context = getApplication()
        ctx.stopService(Intent(ctx, SimultForegroundService::class.java))
    }

    fun stop() {
        bidiSession?.stop()
        bidiSession = null
        recorder?.stop()
        recorder = null
        replaying = false
        stopForegroundService()
        _state.update { it.copy(running = false, partial = "", speaking = false) }
    }

    // ---- 双向同传（Whisper + VAD）----

    /**
     * 启动双向同传：left = 我方语言，right = 对方语言。
     *
     * 依赖云端下发的 Whisper 多语种模型（manifest kind="asr-offline"）。
     * 模型未就绪时明确报错，不做静默降级——因为用中文模型硬跑西语只会出乱码。
     */
    fun startBidirectional(left: LangEnum, right: LangEnum) {
        if (_state.value.running || _state.value.loading) return
        if (left == right) {
            _state.update { it.copy(error = "两侧语言不能相同") }
            return
        }

        refreshExternalModel()
        resolver.leftLanguage = left
        resolver.rightLanguage = right
        resolver.reset()

        _state.update {
            it.copy(
                loading = true,
                error = null,
                leftLang = left,
                rightLang = right,
                bidirectional = true,
            )
        }

        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { whisper.initialize() }
            if (!ok) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "多语种语音模型未就绪。请在「模型」页下载 Whisper 模型" +
                            "（清单 kind 填 asr-offline），再回到这里启动。",
                    )
                }
                return@launch
            }

            val s = settingsStore.settings.first()
            engineId = EngineRegistry.DEFAULT_ENGINE_ID
            engineConfig = s.configFor(engineId)

            val session = BidirectionalSession(
                context = getApplication(),
                whisper = whisper,
                repo = repo,
                resolver = resolver,
                engineId = engineId,
                engineConfig = engineConfig,
                scope = viewModelScope,
                onEvent = { ev -> handleBidiEvent(ev) },
            )
            bidiSession = session
            session.start()
            startForegroundService()
            _state.update { it.copy(loading = false, running = true) }
        }
    }

    private fun handleBidiEvent(ev: BidirectionalSession.Event) {
        when (ev) {
            is BidirectionalSession.Event.Speaking ->
                _state.update { it.copy(speaking = ev.speaking) }

            is BidirectionalSession.Event.Recognized -> {
                val line = SubtitleLine(
                    segmentId = ev.segmentId,
                    original = ev.text,
                    side = ev.side,
                    directionLabel = ev.directionLabel,
                )
                _state.update { it.copy(lines = it.lines + line) }
                publishToOverlay()
            }

            is BidirectionalSession.Event.Translated -> {
                _state.update { st ->
                    st.copy(
                        lines = st.lines.map {
                            if (it.segmentId == ev.segmentId) {
                                it.copy(
                                    translated = ev.text,
                                    translating = false,
                                    failed = ev.failed,
                                )
                            } else it
                        },
                    )
                }
                publishToOverlay()
                if (ttsEnabled && !ev.failed) ev.text?.let { tts.speak(it) }
            }

            is BidirectionalSession.Event.Error ->
                _state.update { it.copy(error = ev.message) }
        }
    }

    /**
     * 测试回放：用 assets 里的 wav 模拟麦克风输入，按实时节奏喂给引擎。
     * 仅 debug 构建暴露，用于无麦克风的模拟器验证完整链路。
     */
    fun startTestReplay(assetPath: String = "asr/test.wav") {
        if (_state.value.running || _state.value.loading) return

        refreshExternalModel()
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { punctuation.initialize() }
            val ok = withContext(Dispatchers.IO) { engine.initialize() }
            if (!ok) {
                _state.update { it.copy(loading = false, error = "语音模型加载失败") }
                return@launch
            }

            val s = settingsStore.settings.first()
            targetLang = if (s.foreignLang == LangEnum.Auto) LangEnum.English else s.foreignLang
            engineId = EngineRegistry.DEFAULT_ENGINE_ID
            engineConfig = s.configFor(engineId)
            _state.update { it.copy(loading = false, running = true) }
            replaying = true

            withContext(Dispatchers.IO) {
                val samples = WavUtil.readFromAssets(getApplication<Application>().assets, assetPath)
                val chunk = 1600 // 100ms
                var i = 0
                while (i < samples.size && replaying) {
                    val end = minOf(i + chunk, samples.size)
                    val upd = engine.accept(samples.copyOfRange(i, end))
                    if (upd != null) handleUpdate(upd)
                    Thread.sleep(100) // 模拟实时节奏
                    i = end
                }
                val fin = engine.flush()
                if (fin != null) handleUpdate(fin)
            }
            replaying = false
            stopForegroundService()
            _state.update { it.copy(running = false, partial = "") }
        }
    }

    @Volatile
    private var replaying = false

    /**
     * 从本地缓存的 manifest 读取已就绪的模型：
     * - kind="asr"          → 切到外部大模型
     * - kind="punctuation"  → 启用标点恢复
     * 找不到或文件不全时回退内置模型 / 不补标点。
     */
    fun refreshExternalModel() {
        val manifest = downloader.cachedManifest()

        val asrEntry = manifest?.models?.firstOrNull {
            it.kind == "asr" && downloader.isReady(it)
        }
        engine.externalPaths = if (asrEntry != null) {
            val enc = downloader.pathOf(asrEntry, "encoder")
            val dec = downloader.pathOf(asrEntry, "decoder")
            val joi = downloader.pathOf(asrEntry, "joiner")
            val tok = downloader.pathOf(asrEntry, "tokens")
            if (enc != null && dec != null && joi != null && tok != null) {
                AsrEngine.ExternalPaths(enc, dec, joi, tok, asrEntry.name)
            } else null
        } else null

        // 多语种（Whisper）—— 双向同传用，只有 encoder/decoder/tokens 三件套
        val offlineEntry = manifest?.models?.firstOrNull {
            it.kind == "asr-offline" && downloader.isReady(it)
        }
        whisper.externalPaths = if (offlineEntry != null) {
            val enc = downloader.pathOf(offlineEntry, "encoder")
            val dec = downloader.pathOf(offlineEntry, "decoder")
            val tok = downloader.pathOf(offlineEntry, "tokens")
            if (enc != null && dec != null && tok != null) {
                WhisperEngine.ExternalPaths(enc, dec, tok, offlineEntry.name)
            } else null
        } else null

        val punctEntry = manifest?.models?.firstOrNull {
            it.kind == "punctuation" && downloader.isReady(it)
        }
        punctuation.setModelPath(
            punctEntry?.let { downloader.pathOf(it, "model") }
        )

        val ttsEntry = manifest?.models?.firstOrNull {
            it.kind == "tts" && downloader.isReady(it)
        }
        if (ttsEntry != null) {
            tts.setModelPaths(
                acousticPath = downloader.pathOf(ttsEntry, "acoustic"),
                vocoderPath = downloader.pathOf(ttsEntry, "vocoder"),
                tokensPath = downloader.pathOf(ttsEntry, "tokens"),
                dictDirPath = downloader.dirOf(ttsEntry.id).absolutePath,
            )
        } else {
            tts.setModelPaths(null, null, null)
        }
    }

    fun clear() {
        _state.update { it.copy(lines = emptyList(), partial = "") }
    }

    private fun handleUpdate(upd: AsrUpdate) {
        when (upd) {
            is AsrUpdate.Partial -> {
                _state.update { it.copy(partial = upd.text) }
                publishToOverlay()
            }

            is AsrUpdate.Final -> {
                // 原始识别结果：连续无标点文本
                val raw = upd.text
                val line = SubtitleLine(
                    segmentId = upd.segmentId,
                    original = raw,
                )
                _state.update {
                    it.copy(partial = "", lines = it.lines + line)
                }
                publishToOverlay()
                // 补标点后用于翻译（改善断句），原文照旧展示
                val punctuated = if (punctuation.isReady) {
                    punctuation.add(raw)
                } else raw
                translateLine(line, punctuated)
            }
        }
    }

    /** 把当前字幕快照推给悬浮窗（未开启时也有开销极小的一次赋值） */
    private fun publishToOverlay() {
        val s = _state.value
        SimultOverlayBus.publish(s.partial, s.lines, s.running)
    }

    private fun translateLine(line: SubtitleLine, textToTranslate: String = line.original) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repo.translate(
                    text = textToTranslate,
                    source = LangEnum.Auto,
                    target = targetLang,
                    engineId = engineId,
                    config = engineConfig,
                )
            }

            val (text, failed) = when (outcome) {
                is TranslateOutcome.Success -> outcome.text to false
                is TranslateOutcome.Failure -> "⚠ ${outcome.message}" to true
            }

            _state.update { st ->
                st.copy(
                    lines = st.lines.map {
                        if (it.segmentId == line.segmentId) {
                            it.copy(translated = text, translating = false, failed = failed)
                        } else it
                    }
                )
            }
            publishToOverlay()

            // 朗读译文（仅成功且用户开启）
            if (ttsEnabled && !failed) {
                tts.speak(text)
            }
        }
    }

    /** TTS 开关（默认关，避免打扰） */
    @Volatile
    var ttsEnabled: Boolean = false
        private set

    fun toggleTts(): Boolean {
        ttsEnabled = !ttsEnabled
        if (!ttsEnabled) tts.stopSpeaking()
        return ttsEnabled
    }

    fun ttsReady(): Boolean = tts.isReady

    // ---- 悬浮窗 ----

    fun startOverlay() = SimultOverlayService.start(getApplication())

    fun stopOverlay() = SimultOverlayService.stop(getApplication())

    fun canDrawOverlay(): Boolean = SimultOverlayService.canDraw(getApplication())

    /**
     * 悬浮窗缺权限时给出可操作的提示。
     * @param manualOnly true 表示连设置页都没跳成功，只能让用户自己找
     */
    fun notifyOverlayNeedsPermission(manualOnly: Boolean) {
        val msg = if (manualOnly) {
            "需要在「设置 → 应用 → 无界译 → 其他权限 → 显示在其他应用上层」手动开启"
        } else {
            "请在弹出的设置页允许「显示在其他应用上层」，返回后再点「悬浮」"
        }
        _state.update { it.copy(error = msg) }
    }

    override fun onCleared() {
        stop()
        stopOverlay()
        SimultOverlayBus.clear()
        engine.release()
        punctuation.release()
        tts.release()
        super.onCleared()
    }
}
