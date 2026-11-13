package dev.beyonddev.beyondlingo.data.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig

/**
 * 标点恢复：给 ASR 输出的连续文本补标点，改善翻译模型的断句质量。
 *
 * 模型（observed，sherpa-onnx 官方文档）：
 * `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8`
 * 只需 model.int8.onnx（72MB），中英双语，官方实测 0.01s/句。
 *
 * 来源：云空间 manifest 里 kind="punctuation"、key="model" 的条目。
 * 未就绪时 [add] 原样返回，不影响主链路。
 */
class PunctuationEngine(private val context: Context) {

    private companion object {
        const val TAG = "PunctuationEngine"
    }

    @Volatile
    private var punct: OfflinePunctuation? = null
    private var modelPath: String? = null

    val isReady: Boolean get() = punct != null

    /** 由 ViewModel 在模型下载完成后调用 */
    fun setModelPath(path: String?) {
        modelPath = path
        release()
    }

    @Synchronized
    fun initialize(): Boolean {
        if (punct != null) return true
        val path = modelPath ?: return false
        return try {
            val config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = path,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
            )
            punct = SherpaExternalLoader.offlinePunctuationOrNull(context, config)
            Log.i(TAG, "punctuation loaded=${punct != null} path=$path")
            punct != null
        } catch (t: Throwable) {
            Log.w(TAG, "init failed: ${t.message}")
            false
        }
    }

    /** 补标点；未就绪或异常时原样返回 */
    fun add(text: String): String {
        if (text.isBlank()) return text
        val p = punct ?: return text
        return try {
            val out = p.addPunctuation(text)
            out.ifBlank { text }
        } catch (t: Throwable) {
            Log.w(TAG, "addPunctuation failed: ${t.message}")
            text
        }
    }

    fun release() {
        try { punct?.release() } catch (_: Throwable) {}
        punct = null
    }
}
