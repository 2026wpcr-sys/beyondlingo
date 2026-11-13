package dev.beyonddev.beyondlingo.data.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * sherpa-onnx 流式识别封装。
 *
 * 模型来源两选一：
 * - 默认 APK 内置 assets（14M 中文 int8，开箱可用）
 * - 外部绝对路径（云空间下载的大模型），走 [SherpaExternalLoader] 反射 newFromFile
 *
 * 对应翻译鸥的 partial / final 语义 + 可调端点阈值。
 */
class AsrEngine(private val context: Context) {

    private companion object {
        const val TAG = "AsrEngine"
        const val SAMPLE_RATE = 16_000

        const val ENCODER = "asr/encoder.int8.onnx"
        const val DECODER = "asr/decoder.int8.onnx"
        const val JOINER = "asr/joiner.int8.onnx"
        const val TOKENS = "asr/tokens.txt"
    }

    /** 外部模型的四个路径；全部非 null 时优先使用 */
    data class ExternalPaths(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val label: String = "external",
    )

    /**
     * 端点检测阈值（秒）。
     * 官方默认 rule2 是 1.2s 静音才出 final，等 final 再翻译会拖慢字幕，
     * 这里降到 0.6s（对应翻译鸥 endpointLatencyAdjustmentLevel 的可调思路）。
     */
    var rule1MinTrailingSilence = 2.0f
    var rule2MinTrailingSilence = 0.6f
    var rule3MinUtteranceLength = 20f

    /** 由 ViewModel 在模型下载完成后设置，null 表示用内置模型 */
    @Volatile
    var externalPaths: ExternalPaths? = null

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastPartial = ""
    private var segmentId = 0
    private var activeLabel = "assets"

    val isReady: Boolean get() = recognizer != null
    val modelLabel: String get() = activeLabel

    @Synchronized
    fun initialize(): Boolean {
        if (recognizer != null) return true
        return try {
            val t0 = System.nanoTime()
            val ext = externalPaths

            val (enc, dec, joi, tok, label) = if (ext != null) {
                listOf(ext.encoder, ext.decoder, ext.joiner, ext.tokens, ext.label)
            } else {
                listOf(ENCODER, DECODER, JOINER, TOKENS, "assets-14M-zh")
            }

            val config = buildConfig(enc, dec, joi, tok)
            recognizer = if (ext != null) {
                val external = SherpaExternalLoader.onlineRecognizerOrNull(context, config)
                if (external != null) {
                    activeLabel = label
                    external
                } else {
                    Log.w(TAG, "external load failed, fallback to builtin assets")
                    activeLabel = "assets-14M-zh(fallback)"
                    OnlineRecognizer(
                        context.assets,
                        buildConfig(ENCODER, DECODER, JOINER, TOKENS),
                    )
                }
            } else {
                activeLabel = label
                OnlineRecognizer(context.assets, config)
            }

            stream = recognizer?.createStream()
            lastPartial = ""
            segmentId = 0
            Log.i(
                TAG,
                "model=$activeLabel loaded in ${(System.nanoTime() - t0) / 1_000_000} ms"
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
            release()
            false
        }
    }

    private fun buildConfig(enc: String, dec: String, joi: String, tok: String) =
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = enc, decoder = dec, joiner = joi,
                ),
                tokens = tok,
                numThreads = 2,
                debug = false,
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = rule1MinTrailingSilence,
                    minUtteranceLength = 0f,
                ),
                rule2 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = rule2MinTrailingSilence,
                    minUtteranceLength = 0f,
                ),
                rule3 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0f,
                    minUtteranceLength = rule3MinUtteranceLength,
                ),
            ),
            enableEndpoint = true,
        )

    /** 喂一段音频，返回本次产生的更新（可能为 null）。由录音线程调用。 */
    @Synchronized
    fun accept(samples: FloatArray): AsrUpdate? {
        val rec = recognizer ?: return null
        val st = stream ?: return null

        st.acceptWaveform(samples, SAMPLE_RATE)
        while (rec.isReady(st)) rec.decode(st)

        if (rec.isEndpoint(st)) {
            val text = rec.getResult(st).text.trim()
            rec.reset(st)
            lastPartial = ""
            return if (text.isEmpty()) null
            else AsrUpdate.Final(text = text, segmentId = segmentId++)
        }

        val partial = rec.getResult(st).text
        return if (partial.isNotEmpty() && partial != lastPartial) {
            lastPartial = partial
            AsrUpdate.Partial(partial)
        } else null
    }

    /** 手动切段，把当前 partial 固化成 final。 */
    @Synchronized
    fun flush(): AsrUpdate.Final? {
        val rec = recognizer ?: return null
        val st = stream ?: return null
        while (rec.isReady(st)) rec.decode(st)
        val text = rec.getResult(st).text.trim()
        rec.reset(st)
        lastPartial = ""
        return if (text.isEmpty()) null else AsrUpdate.Final(text, segmentId++)
    }

    @Synchronized
    fun release() {
        try { stream?.release() } catch (_: Throwable) {}
        try { recognizer?.release() } catch (_: Throwable) {}
        stream = null
        recognizer = null
        lastPartial = ""
    }
}

sealed interface AsrUpdate {
    data class Partial(val text: String) : AsrUpdate
    data class Final(val text: String, val segmentId: Int) : AsrUpdate
}
