package dev.beyonddev.beyondlingo.data.asr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 云端 ASR：Groq（OpenAI 兼容接口）。
 *
 * 为什么接这个：本地 Whisper base int8 在真实对话上精度不足（实测同一段音频
 * 翻译鸥能完全识别、本地识别不了）。Groq 提供 **Whisper large-v3**，
 * 官方标称 WER 10.3%，价格 $0.111/小时（10 分钟对话约 $0.019），
 * 189× 实时，多语种。
 *
 * 代价（必须在 UI 上说清）：
 * - **音频会上传到 Groq 服务器**，不再是纯本地
 * - 需要 API Key + 联网；无网时只能退回本地模型
 *
 * 接口要点（官方文档）：
 * - `POST https://api.groq.com/openai/v1/audio/transcriptions`
 * - `language` 传 ISO-639-1 可提升精度与延迟 —— 正好对应我们「按钮固定语种」
 * - `prompt` 最多 224 token，可给上下文
 * - 服务端会自己降采样到 16kHz mono，所以直接传 16k mono WAV 即可
 */
class CloudAsrEngine {

    companion object {
        private const val TAG = "CloudAsr"

        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
        const val DEFAULT_MODEL = "whisper-large-v3"

        /** 可选模型：turbo 更便宜更快，large-v3 更准 */
        val MODELS = listOf(
            DEFAULT_MODEL to "whisper-large-v3（最准 · 10.3% WER）",
            "whisper-large-v3-turbo" to "whisper-large-v3-turbo（更快 · 12% WER）",
        )
    }

    data class Result(
        val text: String,
        /** 服务端返回的语言（verbose_json） */
        val lang: String,
        /** 平均对数概率，越接近 0 越可信；null 表示未返回 */
        val avgLogProb: Double? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @param language ISO-639-1（zh / es / en …）。固定语种能明显提升精度
     * @param prompt 上下文提示（≤224 token），可空
     * @param apiKey Groq API Key
     * @param model [MODELS] 里的模型 id
     * @param baseUrl 允许自建代理；默认官方地址
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String,
        prompt: String? = null,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        baseUrl: String = DEFAULT_BASE_URL,
    ): Result? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "missing api key")
            return@withContext null
        }
        if (samples.isEmpty()) return@withContext null

        val wav = WavUtil.encode16kMono(samples, 16000)

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "audio.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
        if (language.isNotBlank()) bodyBuilder.addFormDataPart("language", language)
        if (!prompt.isNullOrBlank()) {
            // Groq 限制 224 token，粗略按 4 字符/token 截断
            bodyBuilder.addFormDataPart("prompt", prompt.take(800))
        }

        val url = baseUrl.trimEnd('/') + "/audio/transcriptions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyBuilder.build())
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code}: ${text.take(300)}")
                    return@use null
                }
                val json = JSONObject(text)
                val out = json.optString("text").trim()
                if (out.isEmpty()) return@use null
                val lang = json.optString("language").orEmpty()
                val logProb = if (json.has("avg_logprob")) json.optDouble("avg_logprob") else null
                Result(text = out, lang = lang, avgLogProb = logProb)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "transcribe failed: ${t.message}")
            null
        }
    }
}
