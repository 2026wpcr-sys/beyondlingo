package dev.beyonddev.beyondlingo.data.asr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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

    @Volatile
    private var playing = false
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
        executor.execute {
            if (stopped.get()) return@execute
            try {
                val audio = engine.generate(text, speakerId, speed)
                if (audio.samples.isEmpty()) return@execute
                playPcm(audio.samples, audio.sampleRate)
            } catch (t: Throwable) {
                Log.w(TAG, "speak failed: ${t.message}")
            }
        }
    }

    private fun playPcm(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(pcm.size * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            playing = true
            track.write(pcm, 0, pcm.size)
            track.play()
            // 粗略等待播放完成（MODE_STATIC 下按总时长）
            val ms = (pcm.size * 1000L / sampleRate)
            Thread.sleep(ms.coerceAtMost(30_000))
        } catch (t: Throwable) {
            Log.w(TAG, "play failed: ${t.message}")
        } finally {
            playing = false
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** 停止当前朗读 */
    fun stopSpeaking() {
        stopped.set(true)
    }

    fun release() {
        stopped.set(true)
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
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
