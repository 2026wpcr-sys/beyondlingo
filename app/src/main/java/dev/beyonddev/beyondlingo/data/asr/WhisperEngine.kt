package dev.beyonddev.beyondlingo.data.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Whisper 非流式识别（双向同传的西语侧）。
 *
 * 为什么是 Whisper（真机 arm64 实测，RMX3823）：
 * - 多语种：中 / 西 / 英 都能识别，官方流式 Zipformer 没有西语
 * - 自带语言检测：结果里的 `lang` 直接给出 en/es/zh，正是双向同传做方向路由的依据
 * - 自带标点与正字法：喂 `Buenos dias, como estas?` 输出 `Buenos días, ¿cómo estás?`
 * - RTF 0.063–0.079（tiny int8），余量 12–15 倍
 *
 * 代价：**非流式**，必须靠 VAD 切段，字幕延迟从「边说边出」变成「一句说完才出」。
 *
 * 模型来源：云空间 manifest（kind="asr-offline"，key 为 encoder/decoder/tokens）。
 * 绝对路径走 [SherpaExternalLoader.offlineRecognizerOrNull] 反射 newFromFile。
 */
class WhisperEngine(private val context: Context) {

    private companion object {
        const val TAG = "WhisperEngine"
        const val SAMPLE_RATE = 16_000

        // APK 内置兜底（当前未打包 Whisper，保留路径以便将来内置）
        const val ENCODER = "whisper/encoder.int8.onnx"
        const val DECODER = "whisper/decoder.int8.onnx"
        const val TOKENS = "whisper/tokens.txt"
    }

    /** 外部模型三件套；全非空时优先使用 */
    data class ExternalPaths(
        val encoder: String,
        val decoder: String,
        val tokens: String,
        val label: String = "external",
    )

    data class Result(
        val text: String,
        /** Whisper 检测到的语言代码：zh / es / en …，空串表示未检测出 */
        val lang: String,
    )

    @Volatile
    var externalPaths: ExternalPaths? = null

    private var recognizer: OfflineRecognizer? = null
    private var activeLabel = "none"

    /**
     * 固定语言的识别器缓存。
     *
     * 实测结论：stream.setOption("language", "es") **不生效**（三种配置在
     * 同一段音频上输出字节级一致）。Whisper 的语言是 decoder 初始 token，
     * 必须在构造 OfflineWhisperModelConfig 时通过 language 字段指定。
     *
     * 轮流对话里左右两侧各一个识别器常驻，切换零延迟；首次创建约 1 秒。
     */
    private val fixedRecognizers = HashMap<String, OfflineRecognizer>()

    /**
     * 保存当前模型路径，供按需创建固定语言识别器。
     * 在 initialize() 成功时记录。
     */
    private var currentPaths: Triple<String, String, String>? = null

    val isReady: Boolean get() = recognizer != null
    val modelLabel: String get() = activeLabel

    @Synchronized
    fun initialize(): Boolean {
        if (recognizer != null) return true
        return try {
            val t0 = System.nanoTime()
            val ext = externalPaths

            recognizer = if (ext != null) {
                val cfg = buildConfig(ext.encoder, ext.decoder, ext.tokens)
                val r = SherpaExternalLoader.offlineRecognizerOrNull(context, cfg)
                if (r != null) {
                    activeLabel = ext.label
                    r
                } else {
                    Log.w(TAG, "external load failed")
                    activeLabel = "failed"
                    null
                }
            } else {
                // 尝试内置 assets（当前 APK 未打包，失败是正常的）
                runCatching {
                    val r = OfflineRecognizer(
                        context.assets,
                        buildConfig(ENCODER, DECODER, TOKENS),
                    )
                    activeLabel = "assets-whisper"
                    r
                }.getOrElse {
                    Log.i(TAG, "no builtin whisper: ${it.message}")
                    activeLabel = "none"
                    null
                }
            }

            if (recognizer != null) {
                currentPaths = if (ext != null) {
                    Triple(ext.encoder, ext.decoder, ext.tokens)
                } else {
                    Triple(ENCODER, DECODER, TOKENS)
                }
                Log.i(
                    TAG,
                    "whisper=$activeLabel loaded in ${(System.nanoTime() - t0) / 1_000_000} ms"
                )
            }
            recognizer != null
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
            release()
            false
        }
    }

    private fun buildConfig(enc: String, dec: String, tok: String) =
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                // 注意：Whisper 的配置项是 (encoder, decoder, language, task, ...)，
                // numThreads/debug 属于 OfflineModelConfig 而非 Whisper 配置。
                whisper = OfflineWhisperModelConfig(
                    encoder = enc,
                    decoder = dec,
                    language = "",       // 空 = 自动检测语言
                    task = "transcribe",
                ),
                tokens = tok,
                numThreads = 2,
                debug = false,
            ),
        )

    /**
     * 用**固定语言**识别一段语音（对话翻译模式用）。
     *
     * 为什么要单独一个识别器：实测 `stream.setOption("language", "es")` 完全不生效
     * （同一段音频三种配置输出字节级一致）。Whisper 的语言是 decoder 的初始 token，
     * 只能在构造 `OfflineWhisperModelConfig(language=...)` 时指定。
     *
     * 每种语言一个识别器常驻，首次创建约 1 秒，之后切换零延迟。
     * 实测收益：消除短片段语种误判（西语短句被判成 en 是双向同传方向错误的根因）。
     */
    @Synchronized
    fun transcribeFixedLanguage(
        samples: FloatArray,
        language: String,
        prompt: String? = null,
    ): Result? {
        if (samples.size < SAMPLE_RATE / 10) return null
        val r = fixedRecognizers[language] ?: createFixedRecognizer(language) ?: return null

        return try {
            val stream = if (!prompt.isNullOrBlank()) r.createStream(prompt) else r.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                r.decode(stream)
                val res = r.getResult(stream)
                val text = res.text.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    val out = if (language.startsWith("zh")) ZhSimplify.toSimplified(text) else text
                    Result(text = out, lang = language)
                }
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fixed[$language] transcribe failed: ${t.message}")
            null
        }
    }

    private fun createFixedRecognizer(language: String): OfflineRecognizer? {
        val paths = currentPaths ?: return null
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = paths.first,
                    decoder = paths.second,
                    language = language,   // 关键：构造期固定语言
                    task = "transcribe",
                ),
                tokens = paths.third,
                numThreads = 2,
                debug = false,
            ),
        )
        return try {
            // 外部绝对路径走反射 newFromFile；assets 路径走公开构造器
            val isFile = paths.first.startsWith("/")
            val r = if (isFile) {
                SherpaExternalLoader.offlineRecognizerOrNull(context, cfg)
            } else {
                OfflineRecognizer(context.assets, cfg)
            }
            if (r != null) {
                fixedRecognizers[language] = r
                Log.i(TAG, "fixed-language recognizer created: $language")
            }
            r
        } catch (t: Throwable) {
            Log.w(TAG, "fixed[$language] create failed: ${t.message}")
            null
        }
    }

    /**
     * 识别一段完整语音（16kHz 单声道 float），语言自动检测。
     * 由 VAD 在检测到一句结束时调用。
     *
     * 注意：`setOption("language", ...)` 实测**不生效**（同一段音频三种配置输出
     * 字节级一致）。需要固定语言请用 [transcribeFixedLanguage]，它在构造
     * OfflineWhisperModelConfig 时指定 language。
     *
     * @param prompt initial_prompt，给 Whisper 的上下文提示。
     */
    @Synchronized
    fun transcribe(
        samples: FloatArray,
        prompt: String? = null,
    ): Result? {
        val rec = recognizer ?: return null
        if (samples.size < SAMPLE_RATE / 10) return null // 少于 100ms 忽略

        return try {
            val stream = if (!prompt.isNullOrBlank()) rec.createStream(prompt) else rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val r = rec.getResult(stream)

                val text = r.text.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    // Whisper 的中文输出可能是繁体（base 倾向繁体，tiny 倾向简体），
                    // 统一转简体保证字幕风格一致。lang 非 zh 时不转，
                    // 避免误伤日文汉字（ja 也用汉字）。
                    val lang = r.lang.orEmpty()
                    val out = if (lang.startsWith("zh")) ZhSimplify.toSimplified(text) else text
                    Result(text = out, lang = lang)
                }
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "transcribe failed: ${t.message}")
            null
        }
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        fixedRecognizers.values.forEach { runCatching { it.release() } }
        fixedRecognizers.clear()
        recognizer = null
        currentPaths = null
        activeLabel = "none"
    }
}
