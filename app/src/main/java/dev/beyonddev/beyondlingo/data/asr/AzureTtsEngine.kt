package dev.beyonddev.beyondlingo.data.asr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Azure TTS（神经语音）—— 与翻译鸥同源的音质方案。
 *
 * 翻译鸥用的是 Azure Speech SDK（libMicrosoft.CognitiveServices.Speech.core.so），
 * 我们走 REST 接口（OkHttp 直调），不需要它的 SDK：
 *
 * ```
 * POST https://<region>.tts.speech.microsoft.com/cognitiveservices/v1
 * Ocp-Apim-Subscription-Key: <key>
 * Content-Type: application/ssml+xml
 * X-Microsoft-OutputFormat: riff-24khz-16bit-mono-pcm
 * User-Agent: beyondlingo
 * Body: <speak version='1.0' ...><voice name='es-MX-DaliaNeural'>...</voice></speak>
 * ```
 *
 * 响应体即 WAV 音频（riff 格式，含头），直接解码播放。
 *
 * 免费额度 F0：每月 50 万字符，对日常对话绰绰有余。
 *
 * 语种到音色的映射：目标语言决定用哪个神经音色（西语用 Dalia，
 * 中文用 Xiaoxiao，英语用 Jenny），音色是该语言里最自然的旗舰音色。
 */
class AzureTtsEngine {

    companion object {
        private const val TAG = "AzureTts"

        /** 默认区域端点；用户可在配置里换区域 */
        const val DEFAULT_REGION = "eastus"

        /** 目标语言 → Azure 神经音色（旗舰自然音色） */
        private val VOICES = mapOf(
            "es" to "es-MX-DaliaNeural",      // 西语（墨西哥）女声，最自然
            "zh" to "zh-CN-XiaoxiaoNeural",   // 中文女声
            "en" to "en-US-JennyNeural",      // 英语女声
        )

        /** 输出格式：WAV 24kHz 16bit 单声道，含 RIFF 头可直接解码 */
        private const val OUTPUT_FORMAT = "riff-24khz-16bit-mono-pcm"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "azure-tts-playback").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val stopped = AtomicBoolean(false)

    @Volatile private var apiKey: String = ""
    @Volatile private var region: String = DEFAULT_REGION

    val isReady: Boolean get() = apiKey.isNotBlank()

    fun configure(key: String, region: String = DEFAULT_REGION) {
        this.apiKey = key.trim()
        this.region = region.trim().ifBlank { DEFAULT_REGION }
    }

    /**
     * 合成并播放。
     * @param lang ISO-639-1（决定用哪个音色）
     */
    fun speak(text: String, lang: String) {
        val key = apiKey
        if (key.isBlank() || text.isBlank()) return
        stopped.set(false)
        executor.execute {
            if (stopped.get()) return@execute
            try {
                val wav = synthesize(text, lang, key)
                    ?: return@execute
                val samples = WavUtil.readFromFile(wav)
                playPcm(samples, 24000)
                wav.delete()
            } catch (t: Throwable) {
                Log.w(TAG, "speak failed: ${t.message}")
            }
        }
    }

    /** 调 REST 接口合成，返回临时 WAV 文件；失败返回 null */
    private fun synthesize(text: String, lang: String, key: String): java.io.File? {
        val voice = VOICES[lang] ?: VOICES["en"]
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val ssml = "<speak version='1.0' xml:lang='$lang'>" +
            "<voice name='$voice'>$escaped</voice></speak>"

        val req = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .addHeader("Ocp-Apim-Subscription-Key", key)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            .addHeader("User-Agent", "beyondlingo")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.bytes()
                if (!resp.isSuccessful || body == null) {
                    Log.w(TAG, "HTTP ${resp.code}: ${body?.decodeToString()?.take(200)}")
                    return null
                }
                // 写临时文件（WavUtil.readFromFile 接受文件）
                val f = java.io.File.createTempFile("aztts", ".wav")
                f.writeBytes(body)
                f
            }
        } catch (t: Throwable) {
            Log.w(TAG, "synthesize failed: ${t.message}")
            null
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
            track.write(pcm, 0, pcm.size)
            track.play()
            val ms = (pcm.size * 1000L / sampleRate)
            Thread.sleep(ms.coerceAtMost(30_000))
        } catch (t: Throwable) {
            Log.w(TAG, "play failed: ${t.message}")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** 停止当前朗读（后续合成也会跳过） */
    fun stopSpeaking() {
        stopped.set(true)
    }
}
