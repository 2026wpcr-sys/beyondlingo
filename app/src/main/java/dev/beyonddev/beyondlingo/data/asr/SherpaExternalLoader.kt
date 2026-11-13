package dev.beyonddev.beyondlingo.data.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig

/**
 * sherpa-onnx 外部路径加载桥。
 *
 * 背景（observed）：AAR v2.0.1 的 Kotlin 包装只暴露 `AssetManager` 构造器，
 * 但 native 层导出了 `newFromFile`（支持绝对路径）。把绝对路径交给公开构造器
 * 会 native 崩溃。这里用 Unsafe.allocateInstance 跳过构造器、反射调 private
 * `newFromFile`，再填回 `ptr` / `config` 字段。
 *
 * 失败一律返回 null，**不做 assets 回退** —— 回退必须用内置模型路径重建 config，
 * 否则仍会把绝对路径喂给 newFromAsset 而崩溃。回退由调用方负责。
 *
 * 见 wayfinder/asr-engine-selection.md「模型外部加载验证」。
 * 风险：依赖内部字段名，升级 AAR 必须复测；长期正解是 fork 上游把方法改 public。
 */
object SherpaExternalLoader {

    private const val TAG = "SherpaExternalLoader"

    fun onlineRecognizerOrNull(
        context: Context,
        config: OnlineRecognizerConfig,
    ): OnlineRecognizer? = constructOrNull(
        klass = OnlineRecognizer::class.java,
        configClass = OnlineRecognizerConfig::class.java,
        config = config,
    )

    /**
     * 非流式识别器（Whisper / SenseVoice / Dolby 等）。
     * JNI 同样导出 `OfflineRecognizer_newFromFile`，反射路径成立（已在 AAR 符号表核实）。
     */
    fun offlineRecognizerOrNull(
        context: Context,
        config: OfflineRecognizerConfig,
    ): OfflineRecognizer? = constructOrNull(
        klass = OfflineRecognizer::class.java,
        configClass = OfflineRecognizerConfig::class.java,
        config = config,
    )

    fun offlinePunctuationOrNull(
        context: Context,
        config: OfflinePunctuationConfig,
    ): OfflinePunctuation? = constructOrNull(
        klass = OfflinePunctuation::class.java,
        configClass = OfflinePunctuationConfig::class.java,
        config = config,
    )

    fun offlineTtsOrNull(
        context: Context,
        config: OfflineTtsConfig,
    ): OfflineTts? = constructOrNull(
        klass = OfflineTts::class.java,
        configClass = OfflineTtsConfig::class.java,
        config = config,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> constructOrNull(
        klass: Class<T>,
        configClass: Class<*>,
        config: Any,
    ): T? = try {
        val unsafeCls = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeCls.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val inst = unsafeCls.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, klass) as T

        val m = klass.getDeclaredMethod("newFromFile", configClass)
            .apply { isAccessible = true }
        val ptr = m.invoke(inst, config) as Long
        require(ptr != 0L) { "newFromFile returned null ptr" }

        klass.getDeclaredField("ptr").apply { isAccessible = true }.setLong(inst, ptr)
        runCatching {
            klass.getDeclaredField("config").apply { isAccessible = true }.set(inst, config)
        }
        Log.i(TAG, "${klass.simpleName} loaded from file, ptr=$ptr")
        inst
    } catch (t: Throwable) {
        Log.w(TAG, "newFromFile failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }
}
