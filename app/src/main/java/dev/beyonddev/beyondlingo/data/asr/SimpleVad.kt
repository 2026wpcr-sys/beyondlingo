package dev.beyonddev.beyondlingo.data.asr

import kotlin.math.sqrt

/**
 * 能量 VAD：把连续音频切成「一句话」。
 *
 * 为什么自己写而不用 Silero VAD：
 * Silero 是另一个模型文件（约 2MB + 要上云），而 Whisper 这条路本来就要求
 * 「整段识别」，用能量判定已经足够，且零依赖零体积。噪声环境下不如 Silero，
 * 后续如需要可替换为 Silero（接口保持一致）。
 *
 * 判定规则：
 * - 短时 RMS 超过 [speechRms] → 认为在说话
 * - 连续静音超过 [silenceMs] → 判定一句结束
 * - 太短的段（< [minSpeechMs]）丢弃，避免咳嗽/敲击触发
 * - 超过 [maxSegmentMs] 强制切段，避免长句无限增长
 *
 * 参数取值的关键（真机实测「识别质量差」后调整）：
 * 切得太碎是识别崩掉的主因之一 —— Whisper 拿到的片段太短时既没有上下文，
 * 语言检测也会飘。所以：
 * - speechRms 从 0.012 降到 0.008：更灵敏，避免把轻声的字切掉
 * - silenceMs 从 650 提到 900：等更久的静音，一段才包含完整句子
 * - minSpeechMs 定为 250：「你好」这类短句约 300-400ms，500 会把它们
 *   当成碎片丢掉（真机实测：说短句时只切出 460ms 的段，被 finishSegment
 *   丢弃，界面毫无反应——「我讲话都不识别」的根因）。250 能保住短句，
 *   又足够滤掉咳嗽/敲击这类 < 200ms 的噪声。
 *
 * 线程约束：由同一个采集线程调用，非线程安全。
 */
class SimpleVad(
    private val speechRms: Float = 0.008f,
    private val silenceMs: Int = 900,
    private val minSpeechMs: Int = 250,
    private val maxSegmentMs: Int = 20_000,
    private val sampleRate: Int = 16_000,
) {

    private val buf = ArrayList<Float>(sampleRate * 8)
    private var silentRunMs = 0
    private var speechMs = 0
    private var inSpeech = false

    /** 供 UI 显示「正在说话」 */
    val isSpeaking: Boolean get() = inSpeech

    fun reset() {
        buf.clear()
        silentRunMs = 0
        speechMs = 0
        inSpeech = false
    }

    /**
     * 喂一块音频。
     * @return 若一句结束则返回完整段的样本，否则 null
     */
    fun feed(chunk: FloatArray): FloatArray? {
        if (chunk.isEmpty()) return null

        val chunkMs = chunk.size * 1000 / sampleRate
        val rms = rms(chunk)

        if (rms >= speechRms) {
            if (!inSpeech) inSpeech = true
            silentRunMs = 0
            speechMs += chunkMs
            buf.addAll(chunk.toList())
        } else {
            if (inSpeech) {
                // 说话中的短暂停顿也要保留，避免把词切断
                silentRunMs += chunkMs
                buf.addAll(chunk.toList())

                if (silentRunMs >= silenceMs) {
                    return finishSegment()
                }
            }
            // 未进入说话状态时直接丢弃静音，不占内存
        }

        // 超长强制切
        if (inSpeech && speechMs >= maxSegmentMs) {
            return finishSegment()
        }
        return null
    }

    /** 采集停止时把残留段吐出来 */
    fun flush(): FloatArray? {
        if (!inSpeech || buf.isEmpty()) {
            reset()
            return null
        }
        return finishSegment()
    }

    private fun finishSegment(): FloatArray? {
        val out = FloatArray(buf.size) { buf[it] }
        val speechEnough = speechMs >= minSpeechMs
        reset()
        return if (speechEnough && out.isNotEmpty()) out else null
    }

    private fun rms(a: FloatArray): Float {
        var sum = 0.0
        for (v in a) sum += v.toDouble() * v
        return sqrt(sum / a.size).toFloat()
    }
}
