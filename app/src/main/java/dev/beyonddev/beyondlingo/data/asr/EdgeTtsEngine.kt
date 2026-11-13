package dev.beyonddev.beyondlingo.data.asr

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Edge 在线 TTS（Microsoft Edge 朗读接口）。
 *
 * 免费、无需注册、无需 Key。协议以 rany2/edge-tts **7.3.1**（2026-11-13 master）为准，
 * 并用 2026-11-13 的真实抓包验证过：同一套参数拿到 `101 Switching Protocols`
 * 且收到 MP3 音频。关键点：
 *
 * - WebSocket：`wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`
 * - 鉴权：`Sec-MS-GEC` v2 token（[EdgeTtsToken]，魔改 SHA-256 + CS64），
 *   `Sec-MS-GEC-Version=2-143.0.3650.75`。微软 2026-10 改过校验，
 *   若某天开始 403，先对照 edge-tts 的最新 release 更新版本号常量。
 * - 每块文本单独一条 WebSocket 连接；单块 SSML ≤ 4096 字节。
 * - 返回 MP3（audio-24khz-48kbitrate-mono-mp3），经 [TtsPlayer] 播放。
 *
 * 失败处理：失败会通过 [EdgeFailure] 回调上报给 UI（可回退本地朗读），
 * 不静默吞掉。`onFailure.playedAny` 为 true 表示前面的块已经播出去了，
 * 此时不要再整段回退本地（会重复播前面已播的部分）。
 */
class EdgeTtsEngine {

    companion object {
        private const val TAG = "EdgeTts"
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val TURN_TIMEOUT_MS = 30_000L
    }

    /** 失败信息：message 给 UI 展示；playedAny 表示本次朗读是否已有音频播出 */
    data class EdgeFailure(val message: String, val playedAny: Boolean)

    open class EdgeTtsException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    class NoAudioException(message: String = "no audio received") : EdgeTtsException(message)

    /** 403（时钟偏差）：携带应累加的偏差秒，由驱动层矫正后重试一次 */
    private class Http403Skew(val deltaSeconds: Double, cause: Throwable) :
        EdgeTtsException("HTTP 403, clock skew ${deltaSeconds}s", cause)

    private val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${EdgeTtsToken.CHROMIUM_MAJOR_VERSION}.0.0.0 Safari/537.36 " +
            "Edg/${EdgeTtsToken.CHROMIUM_MAJOR_VERSION}.0.0.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 流式音频，读超时要长
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "edge-tts-playback").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val stopped = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val player = TtsPlayer()

    @Volatile
    private var activeWs: WebSocket? = null

    /** Edge 无需 Key，始终就绪（是否选中由调用方按 provider 决定） */
    val isReady: Boolean get() = true

    /**
     * 合成并播放；[lang] 是 ISO-639-1（决定音色）。
     *
     * 失败时调 [onFailure]（UI 线程外回调，不要在里面做耗时操作）；
     * 全部块播完时调 [onDone]（含被 [stop] 打断的情况）；被中断时不调 [onFailure]。
     */
    fun speak(
        text: String,
        lang: String,
        speed: Float = 1.0f,
        onFailure: ((EdgeFailure) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        // 提交前先清 stopped 并换代：调用方常在 speak 前调过 stop()（stopped=true），
        // 若不在提交侧复位，新任务进 executor 后看到 true 直接静默退出。
        // generation+1 让旧任务（若有）的 gen 检查失败而退出。
        generation.incrementAndGet()
        val gen = generation.get()
        stopped.set(false)
        executor.execute {
            if (gen != generation.get() || stopped.get()) {
                onDone?.invoke()
                return@execute
            }
            player.reset()
            val voice = EdgeTtsCodec.voiceFor(lang)
            val rate = EdgeTtsCodec.rateFor(speed)
            var playedAny = false
            try {
                val chunks = EdgeTtsCodec.prepareChunks(text)
                for (chunk in chunks) {
                    if (stopped.get() || gen != generation.get()) {
                        onDone?.invoke()
                        return@execute
                    }
                    val mp3 = synthChunk(chunk, voice, rate)
                    if (stopped.get() || gen != generation.get()) {
                        onDone?.invoke()
                        return@execute
                    }
                    player.playMp3(mp3)
                    playedAny = true
                }
                onDone?.invoke() // 全部块播完
            } catch (t: Throwable) {
                if (stopped.get() || gen != generation.get()) {
                    onDone?.invoke()
                    return@execute
                }
                Log.w(TAG, "speak failed: ${t.message}")
                onFailure?.invoke(EdgeFailure(friendlyMessage(t), playedAny))
            }
        }
    }

    /** 停止当前朗读（正在合成的 + 正在播的 + 排队中的）；幂等 */
    fun stop() {
        generation.incrementAndGet()
        stopped.set(true)
        activeWs?.cancel()
        player.stop()
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    // ---- 单块合成：一次 WebSocket turn ----

    private fun synthChunk(escapedChunk: String, voice: String, rate: String): ByteArray {
        var attempts = 0
        while (true) {
            try {
                return runTurn(escapedChunk, voice, rate)
            } catch (e: Http403Skew) {
                if (attempts >= 1) throw e
                attempts++
                EdgeTtsToken.adjustClockSkew(e.deltaSeconds)
                // 握手即 403，尚未产出音频，重连重发本块
            }
        }
    }

    private class TurnSession {
        val sawAudio = AtomicBoolean(false)
        val turnEnd = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()
        val skew403 = AtomicReference<Http403Skew?>()
        val latch = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
    }

    private fun runTurn(escapedChunk: String, voice: String, rate: String): ByteArray {
        val session = TurnSession()
        val ts = dateToString()
        val url = "$WSS_URL?TrustedClientToken=${EdgeTtsToken.TRUSTED_CLIENT_TOKEN}" +
            "&ConnectionId=${connectId()}" +
            "&Sec-MS-GEC=${EdgeTtsToken.generate()}" +
            "&Sec-MS-GEC-Version=${EdgeTtsToken.SEC_MS_GEC_VERSION}"
        val req = Request.Builder().url(url).apply {
            header("User-Agent", userAgent)
            header("Accept-Encoding", "gzip, deflate, br, zstd")
            header("Accept-Language", "en-US,en;q=0.9")
            header("Pragma", "no-cache")
            header("Cache-Control", "no-cache")
            header("Origin", ORIGIN)
            header("Sec-WebSocket-Version", "13")
            header("Cookie", "muid=${muid()};")
        }.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped.get()) {
                    webSocket.close(1000, "cancelled")
                    return
                }
                try {
                    webSocket.send(speechConfigFrame(ts))
                    webSocket.send(ssmlFrame(ts, EdgeTtsCodec.buildSsml(voice, escapedChunk, rate)))
                } catch (t: Throwable) {
                    session.error.set(t)
                    webSocket.close(1000, "client error")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get()) {
                    webSocket.close(1000, "cancelled")
                    return
                }
                try {
                    val (head, _) = EdgeTtsCodec.parseTextFrame(text)
                    when (head["Path"]) {
                        "audio.metadata" -> Unit // 词/句边界，本应用不需要
                        "turn.end" -> {
                            session.turnEnd.set(true)
                            webSocket.close(1000, "done")
                        }
                        "turn.start", "response" -> Unit
                        else -> throw EdgeTtsException("unknown text path: ${head["Path"]}")
                    }
                } catch (t: Throwable) {
                    session.error.set(t)
                    webSocket.close(1000, "client error")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (stopped.get()) {
                    webSocket.close(1000, "cancelled")
                    return
                }
                try {
                    val (_, payload) = EdgeTtsCodec.parseBinaryFrame(bytes.toByteArray())
                    if (payload.isEmpty()) return // 流尾空帧，正常信号
                    synchronized(session.audio) { session.audio.write(payload) }
                    session.sawAudio.set(true)
                } catch (t: Throwable) {
                    session.error.set(t)
                    webSocket.close(1000, "client error")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                try {
                    if (response != null && response.code == 403) {
                        // 握手阶段 403：用 Date 头算偏差 → 上层重试一次
                        try {
                            session.skew403.set(
                                Http403Skew(deltaFromDateHeader(response.header("Date")), t),
                            )
                        } catch (se: Throwable) {
                            session.error.set(se)
                        }
                    } else {
                        session.error.set(t)
                    }
                } finally {
                    response?.close()
                    session.latch.countDown()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session.latch.countDown()
            }
        }

        val ws = client.newWebSocket(req, listener)
        activeWs = ws
        try {
            if (!session.latch.await(TURN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                ws.cancel()
                throw EdgeTtsException("turn did not end within ${TURN_TIMEOUT_MS}ms")
            }
        } catch (ie: InterruptedException) {
            ws.cancel()
            Thread.currentThread().interrupt()
            throw EdgeTtsException("interrupted", ie)
        } finally {
            if (activeWs === ws) activeWs = null
        }

        if (stopped.get()) throw java.util.concurrent.CancellationException("cancelled")
        session.skew403.get()?.let { throw it }
        session.error.get()?.let { throw it }
        if (!session.sawAudio.get()) throw NoAudioException()
        if (!session.turnEnd.get()) throw EdgeTtsException("connection closed before turn.end")
        return session.audio.toByteArray()
    }

    // ---- 帧构造 ----

    private fun speechConfigFrame(ts: String): String =
        "X-Timestamp:$ts\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n"

    private fun ssmlFrame(ts: String, ssml: String): String =
        "X-RequestId:${connectId()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${ts}Z\r\n" + // 末尾 Z 为原仓库刻意保留（Edge bug 兼容），勿删
            "Path:ssml\r\n\r\n" +
            ssml

    /** JS 风格时间串："Thu Sep 04 2026 12:00:00 GMT+0000 (Coordinated Universal Time)" */
    private fun dateToString(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.ENGLISH,
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun muid(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02X".format(it) }
    }

    /** 解析 RFC 1123 Date 头，返回（服务器 − 本地）秒偏差；失败抛错 */
    private fun deltaFromDateHeader(dateHeader: String?): Double {
        if (dateHeader == null) throw EdgeTtsException("no Date header in 403 response")
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            fmt.parse(dateHeader)!!.time / 1000.0 -
                (System.currentTimeMillis() / 1000.0)
        } catch (t: Throwable) {
            throw EdgeTtsException("cannot parse server Date: $dateHeader", t)
        }
    }

    private fun friendlyMessage(t: Throwable): String = when (t) {
        is NoAudioException -> "Edge 朗读失败：没有收到音频，请检查网络"
        is Http403Skew -> "Edge 服务暂时不可用（校验失败），请稍后重试"
        is EdgeTtsException -> when {
            t.message?.contains("timed out", ignoreCase = true) == true ||
                t.message?.contains("did not end", ignoreCase = true) == true ->
                "Edge 朗读超时，请检查网络"
            t.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                t.message?.contains("failed to connect", ignoreCase = true) == true ->
                "无网络连接，Edge 朗读失败"
            else -> "Edge 朗读失败：${t.message}"
        }
        is java.net.UnknownHostException -> "无网络连接，Edge 朗读失败"
        is java.net.ConnectException -> "无网络连接，Edge 朗读失败"
        else -> "Edge 朗读失败：${t.message}"
    }
}
