package dev.beyonddev.beyondlingo.data.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/**
 * Yandex 翻译免费直连引擎。
 *
 * 协议来源：STranslate `Plugins/STranslate.Plugin.Translate.YandexBuiltIn/Main.cs`
 * （MIT，作者 zggsong）
 *
 * ```
 * POST https://translate.yandex.net/api/v1/tr.json/translate
 *      ?ucid={随机GUID}&srv=android&format=text
 * UA: ru.yandex.translate/3.20.2024
 * Body: text={text}&lang={src}-{tgt}
 * ```
 *
 * 响应：`{"text":["译文"]}`
 *
 * 关键：
 * - `ucid` 是随机 UUID（去连字符），**360 秒过期**后换新
 * - `srv=android` 伪装 Android 客户端
 * - `User-Agent` 必须设置，否则可能被拒
 */
class YandexEngine : TranslateEngine {

    override val id = "yandex"
    override val displayName = "Yandex 翻译"
    override val requiresApiKey = false

    /** ucid 缓存（360s 有效期），实例内共享 */
    @Volatile
    private var cachedUcid: String? = null

    @Volatile
    private var ucidExpireAt: Long = 0L

    override fun supports(lang: LangEnum): Boolean = toCode(lang) != null

    override fun supportedTargets(): List<LangEnum> =
        LangEnum.allTargets().filter { supports(it) }

    override suspend fun translate(
        text: String,
        source: LangEnum,
        target: LangEnum,
        config: EngineConfig,
    ): TranslateOutcome {
        val src = toCode(source)
            ?: return TranslateOutcome.Failure(EngineErrors.UNSUPPORTED_SOURCE)
        val tgt = toCode(target)
            ?: return TranslateOutcome.Failure(EngineErrors.UNSUPPORTED_TARGET)

        val started = System.currentTimeMillis()

        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("ucid", currentUcid())
            .addQueryParameter("srv", "android")
            .addQueryParameter("format", "text")
            .build()

        val form = FormBody.Builder()
            .add("text", text)
            .add("lang", if (src == "auto") tgt else "$src-$tgt")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()

        return withContext(Dispatchers.IO) {
            Http.executeAsOutcome(request).fold(
                onSuccess = { body -> parse(body, started) },
                onFailure = { Http.toFailure(it) },
            )
        }
    }

    /**
     * 取当前 ucid，过期则重新生成。
     *
     * 参照 STranslate 的 `CachedObject<Guid>`，有效期 360 秒。
     * 加锁避免并发请求同时刷新。
     */
    @Synchronized
    private fun currentUcid(): String {
        val now = System.currentTimeMillis()
        val cached = cachedUcid
        if (cached != null && now < ucidExpireAt) return cached

        val fresh = UUID.randomUUID().toString().replace("-", "")
        cachedUcid = fresh
        ucidExpireAt = now + UCID_TTL_MS
        return fresh
    }

    private fun parse(body: String, startedAt: Long): TranslateOutcome {
        return try {
            val root = JSONObject(body)

            // 部分错误响应会带 code
            val code = root.optInt("code", 200)
            if (code != 200) {
                val extra = root.optString("message").takeIf { it.isNotBlank() }
                    ?.let { "：$it" }.orEmpty()
                return TranslateOutcome.Failure(
                    message = "Yandex 返回错误码 $code$extra",
                    retryable = code >= 500,
                )
            }

            val arr = root.optJSONArray("text")
                ?: return TranslateOutcome.Failure(
                    EngineErrors.PARSE_FAILED,
                    IllegalStateException("missing 'text': ${body.take(200)}"),
                )

            if (arr.length() == 0) {
                return TranslateOutcome.Failure(EngineErrors.EMPTY_RESULT, retryable = true)
            }

            val result = arr.getString(0)
            if (result.isBlank()) {
                return TranslateOutcome.Failure(EngineErrors.EMPTY_RESULT, retryable = true)
            }

            val rawLang = root.optString("lang")
            TranslateOutcome.Success(
                text = result,
                detectedSource = LangEnum.fromCode(rawLang.substringBefore('-'))
                    .takeIf { it != LangEnum.Auto && rawLang.isNotBlank() },
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: Exception) {
            TranslateOutcome.Failure(EngineErrors.PARSE_FAILED, e, retryable = true)
        }
    }

    /** LangEnum → Yandex 语言代码（ISO 639-1）。维吾尔语不支持。 */
    private fun toCode(lang: LangEnum): String? = when (lang) {
        LangEnum.Auto -> "auto"
        LangEnum.ChineseSimplified -> "zh"
        LangEnum.ChineseTraditional -> "zh"
        LangEnum.Cantonese -> "zh"
        LangEnum.English -> "en"
        LangEnum.Japanese -> "ja"
        LangEnum.Korean -> "ko"
        LangEnum.French -> "fr"
        LangEnum.Spanish -> "es"
        LangEnum.Russian -> "ru"
        LangEnum.German -> "de"
        LangEnum.Italian -> "it"
        LangEnum.Turkish -> "tr"
        LangEnum.PortuguesePortugal -> "pt"
        LangEnum.PortugueseBrazil -> "pt"
        LangEnum.Vietnamese -> "vi"
        LangEnum.Indonesian -> "id"
        LangEnum.Thai -> "th"
        LangEnum.Malay -> "ms"
        LangEnum.Arabic -> "ar"
        LangEnum.Hindi -> "hi"
        LangEnum.MongolianCyrillic -> "mn"
        LangEnum.MongolianTraditional -> "mn"
        LangEnum.Khmer -> "km"
        LangEnum.NorwegianBokmal -> "no"
        LangEnum.NorwegianNynorsk -> "no"
        LangEnum.Persian -> "fa"
        LangEnum.Swedish -> "sv"
        LangEnum.Polish -> "pl"
        LangEnum.Dutch -> "nl"
        LangEnum.Ukrainian -> "uk"
        LangEnum.Uzbek -> "uz"
        LangEnum.Uyghur -> null   // STranslate 原实现即不支持
    }

    private companion object {
        const val ENDPOINT = "https://translate.yandex.net/api/v1/tr.json/translate"
        const val USER_AGENT = "ru.yandex.translate/3.20.2024"
        const val UCID_TTL_MS = 360_000L
    }
}
