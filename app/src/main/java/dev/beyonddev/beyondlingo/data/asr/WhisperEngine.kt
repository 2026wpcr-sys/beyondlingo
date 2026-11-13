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
     * 识别一段完整语音（16kHz 单声道 float）。
     * 由 VAD 在检测到一句结束时调用。
     */
    @Synchronized
    fun transcribe(samples: FloatArray): Result? {
        val rec = recognizer ?: return null
        if (samples.size < SAMPLE_RATE / 10) return null // 少于 100ms 忽略

        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val r = rec.getResult(stream)
            stream.release()

            val text = r.text.trim()
            if (text.isEmpty()) null else Result(text = text, lang = r.lang.orEmpty())
        } catch (t: Throwable) {
            Log.w(TAG, "transcribe failed: ${t.message}")
            null
        }
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        activeLabel = "none"
    }
}
