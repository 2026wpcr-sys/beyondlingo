package dev.beyonddev.beyondlingo.data.asr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 云端 TTS —— 可切换服务商。
 *
 * 为什么做成通用：Azure 注册门槛高（部分国家/地区账号受限），需要让用户
 * 能换成任意服务。当前支持两种协议族：
 *
 * 1. [Provider.AZURE] —— 微软 Azure 神经语音（翻译鸥同款）
 *    POST https://{region}.tts.speech.microsoft.com/cognitiveservices/v1
 *    Ocp-Apim-Subscription-Key + SSML body → WAV
 *
 * 2. [Provider.OPENAI] —— OpenAI 兼容的 /audio/speech（覆盖面最广）
 *    POST {baseUrl}{path}  例：https://api.openai.com/v1/audio/speech
 *    Authorization: Bearer {key}
 *    {"model":"tts-1","input":"文本","voice":"alloy","response_format":"wav"} → WAV
 *
 *    兼容服务示例（填各自 baseUrl + model + voice 即可）：
 *    - OpenAI:        https://api.openai.com/v1        tts-1 / alloy
 *    - 硅基流动:      https://api.siliconflow.cn/v1    FunAudioLLM/CosyVoice2-0.5B / ...
 *    - MiniMax / 火山 / 其他自建：按其 OpenAI 兼容文档填
 *
 * 3. [Provider.LOCAL] —— 不配置，用本地 Matcha（由 TtsEngine 处理）
 *
 * 音频统一按 WAV(PCM16) 解码播放；若服务返回 mp3 会解码失败并记日志，
 * 此时请在服务侧把 response_format 设成 wav。
 */
class CloudTtsEngine {

    companion object {
        private const val TAG = "CloudTts"

        const val AZURE_DEFAULT_REGION = "eastus"
        const val OPENAI_DEFAULT_BASE = "https://api.openai.com/v1"
        const val OPENAI_DEFAULT_PATH = "/audio/speech"
        const val OPENAI_DEFAULT_MODEL = "tts-1"
        const val OPENAI_DEFAULT_VOICE = "alloy"
    }

    enum class Provider(val id: String, val label: String) {
        LOCAL("local", "本地语音"),
        AZURE("azure", "Azure 神经语音"),
        OPENAI("openai", "OpenAI 兼容接口"),
        ;

        companion object {
            fun from(id: String?): Provider =
                entries.firstOrNull { it.id == id } ?: LOCAL
        }
    }

    /**
     * OpenAI 兼容 TTS 的常用预设，点一下填好 baseUrl / model / voice。
     *
     * 注意音色格式：硅基流动要求 `模型名:音色名`，和 OpenAI 的 `alloy` 不同。
     */
    data class Preset(
        val name: String,
        val baseUrl: String,
        val path: String,
        val model: String,
        val voice: String,
        val note: String,
    )

    object Presets {
        val all = listOf(
            Preset(
                name = "硅基流动",
                baseUrl = "https://api.siliconflow.cn/v1",
                path = "/audio/speech",
                model = "FunAudioLLM/CosyVoice2-0.5B",
                voice = "FunAudioLLM/CosyVoice2-0.5B:anna",
                note = "中文/英/日/韩强，西语未官方支持",
            ),
            Preset(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                path = "/audio/speech",
                model = "tts-1",
                voice = "alloy",
                note = "多语种含西语，付费",
            ),
        )
    }

    /** 一份完整的 TTS 配置（由 SettingsStore 提供） */
    data class Config(
        val provider: Provider = Provider.LOCAL,
        val apiKey: String = "",
        /** Azure 用：区域；OpenAI 兼容用：baseUrl */
        val endpoint: String = "",
        /** OpenAI 兼容用：请求路径 */
        val path: String = OPENAI_DEFAULT_PATH,
        /** OpenAI 兼容用：模型名 */
        val model: String = OPENAI_DEFAULT_MODEL,
        /** OpenAI 兼容用：音色 */
        val voice: String = OPENAI_DEFAULT_VOICE,
        /** Azure 用：音色（留空则按语言自动选） */
        val azureVoice: String = "",
    ) {
        val usable: Boolean
            get() = when (provider) {
                Provider.LOCAL -> false
                Provider.AZURE -> apiKey.isNotBlank()
                Provider.OPENAI -> apiKey.isNotBlank() && endpoint.isNotBlank()
            }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloud-tts-playback").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val stopped = AtomicBoolean(false)

    @Volatile private var config: Config = Config()

    val isReady: Boolean get() = config.usable

    fun configure(c: Config) {
        config = c
    }

    /** 合成并播放；[lang] 是 ISO-639-1（决定 Azure 音色 / 传给 OpenAI 兼容服务） */
    fun speak(text: String, lang: String) {
        val c = config
        if (!c.usable || text.isBlank()) return
        stopped.set(false)
        executor.execute {
            if (stopped.get()) return@execute
            try {
                val bytes = when (c.provider) {
                    Provider.AZURE -> synthAzure(text, lang, c)
                    Provider.OPENAI -> synthOpenAi(text, lang, c)
                    Provider.LOCAL -> null
                } ?: return@execute
                val decoded = decodeWav(bytes)
                if (decoded == null || decoded.first.isEmpty()) {
                    Log.w(TAG, "decode failed (非 WAV/PCM16？检查 response_format)")
                    return@execute
                }
                playPcm(decoded.first, decoded.second)
            } catch (t: Throwable) {
                Log.w(TAG, "speak failed: ${t.message}")
            }
        }
    }

    // ---- Azure ----
    private fun synthAzure(text: String, lang: String, c: Config): ByteArray? {
        val region = c.endpoint.trim().ifBlank { AZURE_DEFAULT_REGION }
        val voice = c.azureVoice.trim().ifBlank { azureVoiceFor(lang) }
        val escaped = escapeXml(text)
        val ssml = "<speak version='1.0' xml:lang='$lang'>" +
            "<voice name='$voice'>$escaped</voice></speak>"
        val req = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .addHeader("Ocp-Apim-Subscription-Key", c.apiKey)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
            .addHeader("User-Agent", "beyondlingo")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        return execute(req)
    }

    // ---- OpenAI 兼容 ----
    private fun synthOpenAi(text: String, lang: String, c: Config): ByteArray? {
        val base = c.endpoint.trim().trimEnd('/')
        val path = c.path.trim().let { if (it.startsWith("/")) it else "/$it" }
        val voice = c.voice.trim().ifBlank { OPENAI_DEFAULT_VOICE }
        val payload = JSONObject().apply {
            put("model", c.model.trim().ifBlank { OPENAI_DEFAULT_MODEL })
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
        }
        val req = Request.Builder()
            .url(base + path)
            .addHeader("Authorization", "Bearer ${c.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "beyondlingo")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return execute(req)
    }

    private fun execute(req: Request): ByteArray? = try {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.bytes()
            if (!resp.isSuccessful || body == null) {
                Log.w(TAG, "HTTP ${resp.code}: ${body?.decodeToString()?.take(200)}")
                null
            } else {
                body
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "request failed: ${t.message}")
        null
    }

    private fun azureVoiceFor(lang: String): String = when (lang) {
        "es" -> "es-MX-DaliaNeural"
        "zh" -> "zh-CN-XiaoxiaoNeural"
        "ja" -> "ja-JP-NanamiNeural"
        "ko" -> "ko-KR-SunHiNeural"
        "fr" -> "fr-FR-DeniseNeural"
        "de" -> "de-DE-KatjaNeural"
        "ru" -> "ru-RU-SvetlanaNeural"
        else -> "en-US-JennyNeural"
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * 解析 WAV（RIFF/PCM16 mono）→ (samples, sampleRate)。
     *
     * 必须从 fmt chunk 读采样率：Azure 返回 24kHz，而硅基流动默认 44.1kHz。
     * 硬编码采样率会导致播放速度不对（音调变尖或变闷）。
     */
    private fun decodeWav(bytes: ByteArray): Pair<FloatArray, Int>? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null

        var pos = 12
        var dataStart = -1
        var dataLen = 0
        var sampleRate = 24000
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            when (id) {
                "fmt " -> {
                    if (pos + 8 + 8 <= bytes.size) {
                        sampleRate = (bytes[pos + 12].toInt() and 0xFF) or
                            ((bytes[pos + 13].toInt() and 0xFF) shl 8) or
                            ((bytes[pos + 14].toInt() and 0xFF) shl 16) or
                            ((bytes[pos + 15].toInt() and 0xFF) shl 24)
                    }
                }
                "data" -> {
                    dataStart = pos + 8
                    dataLen = minOf(size, bytes.size - dataStart)
                }
            }
            if (dataStart > 0) break
            pos += 8 + size + (size and 1)
        }
        if (dataStart <= 0 || dataLen <= 0) return null

        val n = dataLen / 2
        val out = FloatArray(n)
        for (k in 0 until n) {
            val lo = bytes[dataStart + k * 2].toInt() and 0xFF
            val hi = bytes[dataStart + k * 2 + 1].toInt()
            out[k] = ((hi shl 8) or lo) / 32768.0f
        }
        return out to sampleRate
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

    fun stopSpeaking() {
        stopped.set(true)
    }
}
