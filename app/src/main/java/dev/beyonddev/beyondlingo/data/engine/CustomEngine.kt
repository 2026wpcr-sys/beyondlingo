package dev.beyonddev.beyondlingo.data.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 可自定义的 OpenAI 兼容引擎。
 *
 * 与 [OpenAiEngine] 的区别：
 * - [OpenAiEngine] 是「开箱即用」的标准实现，配置项固定
 * - 本引擎强调**完全自定义**：服务地址、模型、鉴权头、请求路径都可改，
 *   并内置常见服务预设（智谱 GLM / DeepSeek / 硅基流动 / Moonshot 等）
 *
 * 协议仍是 OpenAI Chat Completions，这是国内大模型最通用的接口形态。
 */
class CustomEngine : TranslateEngine {

    override val id = "custom"
    override val displayName = "自定义引擎"
    override val requiresApiKey = true

    override fun supports(lang: LangEnum): Boolean = lang != LangEnum.Auto

    override fun supportedTargets(): List<LangEnum> = LangEnum.allTargets()

    override suspend fun translate(
        text: String,
        source: LangEnum,
        target: LangEnum,
        config: EngineConfig,
    ): TranslateOutcome {
        if (config.baseUrl.isBlank()) {
            return TranslateOutcome.Failure(EngineErrors.MISSING_BASE_URL)
        }
        if (config.model.isBlank()) {
            return TranslateOutcome.Failure(EngineErrors.MISSING_MODEL)
        }
        if (target == LangEnum.Auto) {
            return TranslateOutcome.Failure(EngineErrors.UNSUPPORTED_TARGET)
        }

        val started = System.currentTimeMillis()

        val systemPrompt = config.systemPrompt.ifBlank {
            buildSystemPrompt(source, target)
        }

        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.3)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }

        val endpoint = buildEndpoint(config.baseUrl, config.apiPath)

        val builder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (config.apiKey.isNotBlank()) {
            val headerName = config.authHeader.ifBlank { "Authorization" }
            val prefix = config.authPrefix.ifBlank { "Bearer " }
            builder.header(headerName, prefix + config.apiKey)
        }

        // 额外的自定义请求头（形如 "key:value" 每行一条）
        if (config.extraHeaders.isNotBlank()) {
            config.extraHeaders.lines().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotEmpty()) builder.header(k, v)
                }
            }
        }

        return withContext(Dispatchers.IO) {
            Http.executeAsOutcome(builder.build()).fold(
                onSuccess = { body -> parse(body, started) },
                onFailure = { Http.toFailure(it) },
            )
        }
    }

    private fun parse(body: String, startedAt: Long): TranslateOutcome {
        return try {
            val root = JSONObject(body)

            // OpenAI 风格错误
            val err = root.optJSONObject("error")
            if (err != null) {
                return TranslateOutcome.Failure(
                    message = err.optString("message", "服务返回错误"),
                    retryable = false,
                )
            }

            // 智谱等服务的头部错误码
            val code = root.optInt("code", 200)
            if (code != 200 && code != 0) {
                val msg = root.optString("msg")
                    .ifBlank { root.optString("message") }
                return TranslateOutcome.Failure(
                    message = "服务返回错误 $code${if (msg.isNotBlank()) "：$msg" else ""}",
                    retryable = code >= 500 || code == 429,
                )
            }

            val choices = root.optJSONArray("choices")
                ?: return TranslateOutcome.Failure(
                    EngineErrors.PARSE_FAILED,
                    IllegalStateException("missing 'choices': ${body.take(300)}"),
                )

            if (choices.length() == 0) {
                return TranslateOutcome.Failure(EngineErrors.EMPTY_RESULT, retryable = true)
            }

            val message = choices.getJSONObject(0).optJSONObject("message")
            val content = message?.optString("content")?.trim()

            if (content.isNullOrBlank()) {
                return TranslateOutcome.Failure(EngineErrors.EMPTY_RESULT, retryable = true)
            }

            TranslateOutcome.Success(
                text = content,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: Exception) {
            TranslateOutcome.Failure(EngineErrors.PARSE_FAILED, e, retryable = true)
        }
    }

    private fun buildSystemPrompt(source: LangEnum, target: LangEnum): String {
        val targetName = target.labelZh
        val sourceHint = if (source == LangEnum.Auto) "先判断原文语言，"
        else "原文是${source.labelZh}，"
        return "你是专业翻译引擎。${sourceHint}将用户输入翻译成$targetName。" +
            "只输出译文本身，不要解释、不要加引号、不要重复原文、不要添加任何前后缀。"
    }

    /**
     * 拼接请求端点。
     *
     * 支持三种配置写法：
     * - 只填域名：`https://open.bigmodel.cn` → 自动补 `/api/paas/v4/chat/completions`? 否
     *   统一按 OpenAI 惯例补 `/v1/chat/completions`
     * - 填到 `/v1`：补 `/chat/completions`
     * - 填完整路径：原样使用
     *
     * 若用户显式填写了 [apiPath]，则优先用它拼接。
     */
    private fun buildEndpoint(baseUrl: String, apiPath: String): String {
        val base = baseUrl.trim().trimEnd('/')

        if (apiPath.isNotBlank()) {
            val p = apiPath.trim().let { if (it.startsWith("/")) it else "/$it" }
            return base + p
        }

        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 常见 OpenAI 兼容服务的预设，用于设置页一键填充。
 */
data class EnginePreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val apiPath: String = "",
    val note: String = "",
)

object CustomEnginePresets {

    val all: List<EnginePreset> = listOf(
        EnginePreset(
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            apiPath = "/chat/completions",
            note = "glm-4-flash 免费额度充足",
        ),
        EnginePreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-chat",
            note = "官方 API，国内可直连",
        ),
        EnginePreset(
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            note = "新用户有免费额度",
        ),
        EnginePreset(
            name = "Moonshot",
            baseUrl = "https://api.moonshot.cn",
            defaultModel = "moonshot-v1-8k",
            note = "Kimi 官方 API",
        ),
        EnginePreset(
            name = "Groq",
            baseUrl = "https://api.groq.com/openai",
            defaultModel = "llama-3.3-70b-versatile",
            note = "速度极快，需海外网络",
        ),
        EnginePreset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com",
            defaultModel = "gpt-4o-mini",
            note = "官方 API，需海外网络",
        ),
    )
}
