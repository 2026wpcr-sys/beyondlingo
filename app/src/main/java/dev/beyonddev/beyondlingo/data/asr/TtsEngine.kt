package dev.beyonddev.beyondlingo.data.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 译文朗读（TTS）。
 *
 * 模型：sherpa-onnx Matcha zh-en（acoustic + vocoder + tokens 三件套），
 * 通过云空间 manifest（kind="tts"）下载。未就绪时 [speak] 静默跳过。
 *
 * 选 Matcha 而非 MeloTTS 的依据：RTF 0.391 vs 2.518（官方 RPi4 数据），
 * 手机端只有 Matcha 有实时余量。见 wayfinder/tts-engine-selection.md。
 *
 * 播放用 AudioTrack 一次性写入（译文通常是短句，无需流式）。
 */
class TtsEngine(private val context: Context) {

    private companion object {
        const val TAG = "TtsEngine"
    }

    private var tts: OfflineTts? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-playback").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** 播放走共用的 TtsPlayer（PCM / MP3 / stop 三路一致） */
    private val player = TtsPlayer(context.cacheDir)

    private val stopped = AtomicBoolean(false)

    // 外部模型路径
    private var acoustic: String? = null
    private var vocoder: String? = null
    private var tokens: String? = null
    private var dictDir: String? = null

    val isReady: Boolean get() = tts != null

    /** 由 ViewModel 在模型下载完成后调用（传 null 关闭 TTS） */
    fun setModelPaths(
        acousticPath: String?,
        vocoderPath: String?,
        tokensPath: String?,
        dictDirPath: String? = null,
    ) {
        acoustic = acousticPath
        vocoder = vocoderPath
        tokens = tokensPath
        dictDir = dictDirPath
        release()
    }

    @Synchronized
    fun initialize(): Boolean {
        if (tts != null) return true
        val a = acoustic ?: return false
        val v = vocoder ?: return false
        val t = tokens ?: return false
        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = a,
                        vocoder = v,
                        lexicon = "",
                        tokens = t,
                        dictDir = dictDir.orEmpty(),
                        // 后三个参数（noiseScale / noiseScaleW / lengthScale）用默认值
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            tts = SherpaExternalLoader.offlineTtsOrNull(context, config)
            Log.i(TAG, "tts loaded=${tts != null}")
            tts != null
        } catch (t: Throwable) {
            Log.w(TAG, "init failed: ${t.message}")
            false
        }
    }

    /** 异步朗读；会打断上一句 */
    fun speak(text: String, speakerId: Int = 0, speed: Float = 1.0f) {
        val engine = tts ?: return
        if (text.isBlank()) return
        stopped.set(false)
        player.reset()
        executor.execute {
            if (stopped.get()) return@execute
            try {
                val audio = engine.generate(text, speakerId, speed)
                if (audio.samples.isEmpty()) return@execute
                player.playPcm(audio.samples, audio.sampleRate)
            } catch (t: Throwable) {
                Log.w(TAG, "speak failed: ${t.message}")
            }
        }
    }

    /** 停止当前朗读 */
    fun stopSpeaking() {
        stopped.set(true)
        player.stop()
    }

    fun release() {
        stopped.set(true)
        player.stop()
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
    }

    /** 彻底释放（含播放线程）；只在 ViewModel 销毁时调用，勿在换模型时调用 */
    fun shutdown() {
        release()
        executor.shutdown()
    }

    /** 从目录约定推断文件名（多用于本地调试） */
    fun probeDir(dir: File): Boolean {
        val a = File(dir, "model-steps-3.onnx")
        val v = File(dir, "generator.onnx")
        val t = File(dir, "tokens.txt")
        if (!a.exists() || !v.exists() || !t.exists()) return false
        setModelPaths(a.absolutePath, v.absolutePath, t.absolutePath)
        return true
    }
}
