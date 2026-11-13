package dev.beyonddev.beyondlingo.data.asr

import android.content.Context
import android.util.Log
import dev.beyonddev.beyondlingo.data.TranslateRepository
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.data.engine.TranslateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 双向同传会话：采集 → VAD 切段 → Whisper 识别 → 方向判定 → 翻译。
 *
 * 线程模型：
 * - 采集线程只做 VAD 切段（纯计算，微秒级），切出的段丢进 Channel
 * - 独立协程消费 Channel，做 Whisper 识别（几百 ms）+ 翻译（网络）
 * - 识别/翻译不阻塞采集，避免丢音
 *
 * 与单向流式路径的区别（见 WAYFINDER.md）：
 * - 流式：边说边出 partial，端点出 final，延迟 ~0.6s
 * - 本类：VAD 判定一句结束才整段识别，延迟 = 静音阈值 + 识别耗时
 */
class BidirectionalSession(
    private val context: Context,
    private val whisper: WhisperEngine,
    private val repo: TranslateRepository,
    private val resolver: BidirectionalResolver,
    private val engineId: String,
    private val engineConfig: EngineConfig,
    private val scope: CoroutineScope,
    private val onEvent: (Event) -> Unit,
    /**
     * 对话翻译模式（双方轮流说，说完一段再翻译）。
     *
     * 非 null 时语种由 UI 按钮**显式指定**，不再靠 Whisper 猜、也不轮流推断：
     * - 传给 Whisper 固定语言（构造期 language 字段），消除短片段语种误判
     * - 方向 = 说的语种 → 另一侧语种，永远正确
     * 这是识别质量最稳的形态。
     */
    private val forcedSpeakSide: BidirectionalResolver.Side? = null,
    /**
     * 云端 ASR（Groq）。非 null 且 apiKey 非空时走云端，否则走本地 Whisper。
     * 云端精度高（whisper-large-v3，WER 10.3%），但音频会上传。
     */
    private val cloudAsr: CloudAsrEngine? = null,
    private val cloudApiKey: String = "",
    private val cloudModel: String = CloudAsrEngine.DEFAULT_MODEL,
) {

    private companion object {
        const val TAG = "BidiSession"
    }

    sealed interface Event {
        /** VAD 说话状态变化，用于 UI 显示「正在说话」 */
        data class Speaking(val speaking: Boolean) : Event

        /** 识别出一句，译文待补 */
        data class Recognized(
            val segmentId: Int,
            val text: String,
            val side: BidirectionalResolver.Side,
            val directionLabel: String,
            val inherited: Boolean,
        ) : Event

        /** 译文就绪 */
        data class Translated(
            val segmentId: Int,
            val text: String?,
            val failed: Boolean,
        ) : Event

        data class Error(val message: String) : Event
    }

    private val segments = Channel<FloatArray>(Channel.UNLIMITED)
    private var recorder: AudioRecorder? = null
    private var consumerJob: kotlinx.coroutines.Job? = null
    @Volatile private var segmentId = 0
    @Volatile private var lastSpeaking = false

    /** 上一轮判定出的语种（Whisper 语言码），用于下一段的语种预判 */
    @Volatile private var lastLangCode: String? = null

    /** 上一轮的识别文本，作为下一段的 initial_prompt 给 Whisper 上下文 */
    @Volatile private var lastText: String = ""

    /** 上一轮判定出的说话方；null 表示还没有第一段 */
    @Volatile private var lastSide: BidirectionalResolver.Side? = null

    val isRunning: Boolean get() = recorder != null

    fun start() {
        if (recorder != null) return

        // 消费协程：识别 + 翻译
        consumerJob = scope.launch(Dispatchers.IO) {
            for (samples in segments) {
                handleSegment(samples)
            }
        }

        val vad = SimpleVad()
        val r = AudioRecorder(
            onSamples = { chunk ->
                // 采集线程：只做 VAD，绝不做识别
                val speaking = vad.isSpeaking || runCatching { rmsOf(chunk) > 0.012f }.getOrDefault(false)
                if (speaking != lastSpeaking) {
                    lastSpeaking = speaking
                    onEvent(Event.Speaking(speaking))
                }
                vad.feed(chunk)?.let { seg -> segments.trySend(seg) }
            },
            onError = { msg -> onEvent(Event.Error(msg)) },
        )
        recorder = r
        r.start()
    }

    fun stop() {
        recorder?.stop()
        recorder = null
        // 把残留段处理掉再关闭
        runCatching { segments.trySend(FloatArray(0)) }
        consumerJob?.cancel()
        consumerJob = null
        lastSpeaking = false
        lastLangCode = null
        lastText = ""
        lastSide = null
    }

    private fun rmsOf(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var s = 0.0
        for (v in a) s += v.toDouble() * v
        return kotlin.math.sqrt(s / a.size).toFloat()
    }

    /** LangEnum → Whisper 语言码（zh / es / en / ja …） */
    private fun langToWhisperCode(lang: dev.beyonddev.beyondlingo.data.engine.LangEnum): String =
        when (lang.code.substringBefore('-').lowercase()) {
            "zh" -> "zh"
            "es" -> "es"
            "en" -> "en"
            "ja" -> "ja"
            "ko" -> "ko"
            "fr" -> "fr"
            "de" -> "de"
            "ru" -> "ru"
            "pt" -> "pt"
            "it" -> "it"
            else -> lang.code.substringBefore('-').lowercase()
        }

    private suspend fun handleSegment(samples: FloatArray) {
        if (samples.isEmpty()) return

        // ---- 对话翻译模式：语种由按钮显式指定 ----
        // 不做自动检测、不轮流推断。方向固定、语言固定，识别质量最稳。
        val forced = forcedSpeakSide
        if (forced != null) {
            val side = forced
            val sourceLang = when (side) {
                BidirectionalResolver.Side.LEFT -> resolver.leftLanguage
                BidirectionalResolver.Side.RIGHT -> resolver.rightLanguage
                else -> return
            }
            val targetLang = when (side) {
                BidirectionalResolver.Side.LEFT -> resolver.rightLanguage
                BidirectionalResolver.Side.RIGHT -> resolver.leftLanguage
                else -> return
            }

            val hint = langToWhisperCode(sourceLang)
            val t0 = System.nanoTime()

            // 云端优先：精度显著高于本地 int8（代价是音频上传）
            val cloudText = if (cloudAsr != null && cloudApiKey.isNotBlank()) {
                cloudAsr.transcribe(
                    samples = samples,
                    language = hint,
                    prompt = lastText.takeIf { it.isNotBlank() },
                    apiKey = cloudApiKey,
                    model = cloudModel,
                )?.text
            } else null

            val res = if (cloudText != null) {
                WhisperEngine.Result(text = cloudText, lang = hint)
            } else {
                if (cloudAsr != null && cloudApiKey.isNotBlank()) {
                    Log.w(TAG, "cloud asr returned null, falling back to local")
                }
                whisper.transcribeFixedLanguage(samples, hint, lastText) ?: return
            }
            val asrMs = (System.nanoTime() - t0) / 1_000_000
            lastText = res.text

            val id = segmentId++
            val label = "${sourceLang.labelZh} → ${targetLang.labelZh}"
            val via = if (cloudText != null) "cloud" else "local"
            Log.i(TAG, "seg#$id fixed=$hint via=$via side=$side asr=${asrMs}ms text='${res.text}'")

            onEvent(
                Event.Recognized(
                    segmentId = id,
                    text = res.text,
                    side = side,
                    directionLabel = label,
                    inherited = false,
                )
            )
            translate(id, res.text, sourceLang, targetLang)
            return
        }

        // ---- 语种与方向判定 ----
        //
        // 关键约束：Whisper 固定语言后，返回的 lang 恒等于提示语种，
        // 若靠 lang 判定方向，方向就永远不会切换。所以：
        //
        //  - 首段：放开自动检测（不固定语言），由 Whisper 判出是谁在说
        //  - 后续段：轮流对话场景下，按「交替」推进方向；
        //    同时给 Whisper 固定上一轮的语种 + initial_prompt 上下文，
        //    避免短片段语种判错和上下文缺失
        //
        // 这套「首段自动 + 之后交替 + 继承上下文」对应翻译鸥的
        // inheritMatchingRecognizingLanguage 思路。

        val firstSegment = lastSide == null
        val hint = if (firstSegment) null else lastLangCode
        val prompt = if (firstSegment) null else lastText.takeIf { it.isNotBlank() }

        val t0 = System.nanoTime()
        val res = whisper.transcribe(samples, prompt = prompt) ?: return
        val asrMs = (System.nanoTime() - t0) / 1_000_000

        val detected = if (firstSegment) {
            // 首段让 Whisper 自己判
            BidirectionalResolver.fromWhisperCode(res.lang)
        } else {
            // 后续段用上一轮判定出的语种（轮流对话下不会变）
            hint?.let { BidirectionalResolver.fromWhisperCode(it) }
        }

        // 方向：首段由 Whisper 判出的语种决定；之后按轮流交替
        val side = if (firstSegment) {
            when (detected) {
                null -> BidirectionalResolver.Side.UNKNOWN
                else -> {
                    // detected 是 LangEnum，与左右槽比对
                    if (detected == resolver.leftLanguage) BidirectionalResolver.Side.LEFT
                    else if (detected == resolver.rightLanguage) BidirectionalResolver.Side.RIGHT
                    else BidirectionalResolver.Side.UNKNOWN
                }
            }
        } else {
            resolver.nextSide(lastSide ?: BidirectionalResolver.Side.UNKNOWN)
        }

        if (side == BidirectionalResolver.Side.UNKNOWN) {
            Log.i(TAG, "skip unresolved: lang='${res.lang}' text='${res.text}'")
            return
        }

        val sourceLang = when (side) {
            BidirectionalResolver.Side.LEFT -> resolver.leftLanguage
            BidirectionalResolver.Side.RIGHT -> resolver.rightLanguage
            else -> null
        }
        val targetLang = when (side) {
            BidirectionalResolver.Side.LEFT -> resolver.rightLanguage
            BidirectionalResolver.Side.RIGHT -> resolver.leftLanguage
            else -> null
        }
        if (sourceLang == null || targetLang == null) return

        val decidedCode = langToWhisperCode(sourceLang)

        if (hint != null && res.lang.isNotEmpty() && !res.lang.startsWith(hint)) {
            Log.i(
                TAG,
                "lang conflict: hint=$hint whisper=${res.lang} text='${res.text}'"
            )
        }

        lastSide = side
        lastLangCode = decidedCode
        lastText = res.text

        val id = segmentId++
        val label = "${sourceLang.labelZh} → ${targetLang.labelZh}"
        Log.i(
            TAG,
            "seg#$id lang=${res.lang} side=$side hint=${hint ?: "-"} " +
                "asr=${asrMs}ms text='${res.text}'"
        )

        onEvent(
            Event.Recognized(
                segmentId = id,
                text = res.text,
                side = side,
                directionLabel = label,
                inherited = !firstSegment,
            )
        )

        val outcome = translate(id, res.text, sourceLang, targetLang)
        when (outcome) {
            is TranslateOutcome.Success ->
                onEvent(Event.Translated(id, outcome.text, false))

            is TranslateOutcome.Failure ->
                onEvent(Event.Translated(id, "⚠ ${outcome.message}", true))
        }
    }

    private suspend fun translate(
        id: Int,
        text: String,
        sourceLang: dev.beyonddev.beyondlingo.data.engine.LangEnum,
        targetLang: dev.beyonddev.beyondlingo.data.engine.LangEnum,
    ): TranslateOutcome = withContext(Dispatchers.IO) {
        repo.translate(
            text = text,
            source = sourceLang,
            target = targetLang,
            engineId = engineId,
            config = engineConfig,
        )
    }
}
