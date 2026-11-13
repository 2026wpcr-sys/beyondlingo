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
    }

    private fun rmsOf(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var s = 0.0
        for (v in a) s += v.toDouble() * v
        return kotlin.math.sqrt(s / a.size).toFloat()
    }

    private suspend fun handleSegment(samples: FloatArray) {
        if (samples.isEmpty()) return

        val t0 = System.nanoTime()
        val res = whisper.transcribe(samples) ?: return
        val asrMs = (System.nanoTime() - t0) / 1_000_000

        val detected = BidirectionalResolver.fromWhisperCode(res.lang)
        val decision = resolver.resolve(detected)

        if (decision.side == BidirectionalResolver.Side.UNKNOWN) {
            Log.i(TAG, "skip unresolved: lang='${res.lang}' text='${res.text}'")
            return
        }

        val id = segmentId++
        val label = "${decision.sourceLang.labelZh} → ${decision.targetLang.labelZh}"
        Log.i(
            TAG,
            "seg#$id lang=${res.lang} side=${decision.side} inherited=${decision.inherited} " +
                "asr=${asrMs}ms text='${res.text}'"
        )

        onEvent(
            Event.Recognized(
                segmentId = id,
                text = res.text,
                side = decision.side,
                directionLabel = label,
                inherited = decision.inherited,
            )
        )

        val outcome = withContext(Dispatchers.IO) {
            repo.translate(
                text = res.text,
                source = decision.sourceLang,
                target = decision.targetLang,
                engineId = engineId,
                config = engineConfig,
            )
        }

        when (outcome) {
            is TranslateOutcome.Success ->
                onEvent(Event.Translated(id, outcome.text, false))

            is TranslateOutcome.Failure ->
                onEvent(Event.Translated(id, "⚠ ${outcome.message}", true))
        }
    }
}
