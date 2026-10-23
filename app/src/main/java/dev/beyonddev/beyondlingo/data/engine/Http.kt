package dev.beyonddev.beyondlingo.data.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 引擎共享的 HTTP 客户端。
 *
 * 单例，避免每个引擎各自建连接池。超时设置为移动网络友好值。
 */
object Http {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        // 免费引擎（Google/Yandex）常用自签或非常规证书链，这里保持默认校验；
        // 如需调试可临时替换 SSLSocketFactory，但不建议在发布版放开。
        .build()

    /**
     * 同步执行请求并返回响应体字符串。
     *
     * 所有网络异常都转成 [TranslateOutcome.Failure]，调用方无需捕获。
     */
    fun executeAsOutcome(request: Request): Result<String> = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Result.failure(EngineHttpException(response.code, body, response.message))
            } else {
                Result.success(body)
            }
        }
    } catch (e: SocketTimeoutException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    /** 把底层异常翻译成面向用户的结果 */
    fun toFailure(t: Throwable): TranslateOutcome.Failure = when (t) {
        is EngineHttpException -> TranslateOutcome.Failure(
            message = "引擎返回 HTTP ${t.code}，请稍后重试或切换引擎",
            cause = t,
            retryable = t.code >= 500 || t.code == 429,
        )
        is SocketTimeoutException -> TranslateOutcome.Failure(
            EngineErrors.TIMEOUT, t, retryable = true,
        )
        is IOException -> TranslateOutcome.Failure(
            EngineErrors.NETWORK, t, retryable = true,
        )
        else -> TranslateOutcome.Failure(
            t.message ?: "未知错误", t, retryable = false,
        )
    }
}

/** HTTP 非 2xx 响应 */
class EngineHttpException(
    val code: Int,
    val body: String,
    val httpMessage: String,
) : IOException("HTTP $code $httpMessage: ${body.take(300)}")
