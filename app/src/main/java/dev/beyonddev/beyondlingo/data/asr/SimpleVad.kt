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
    /**
     * 说话判定阈值。实测真机安静室内底噪 rms≈0.02-0.05、正常说话≈0.13：
     * 0.008 会被底噪压住（VAD 恒为「在说话」，噪声被 Whisper 幻觉成句子），
     * 0.06 恰在底噪之上、说话之下。
     */
    private val speechRms: Float = 0.06f,
    private val silenceMs: Int = 900,
    private val minSpeechMs: Int = 250,
    private val maxSegmentMs: Int = 20_000,
    private val sampleRate: Int = 16_000,
    /** 噪声基线自适应：说话期间 rms 长期居高时按此斜率抬阈值（每秒），防止长录音卡死 */
    private val noiseAdaptRatePerSec: Float = 0.05f,
    /** 自适应基线相对固定阈值的最大倍数上限，防止把真语音也滤掉 */
    private val noiseAdaptMaxFactor: Float = 2.5f,
) {

    private val buf = ArrayList<Float>(sampleRate * 8)
    private var silentRunMs = 0
    private var speechMs = 0
    private var inSpeech = false

    /** 持续「高于阈值但从未停顿」时缓慢抬升的有效阈值 */
    private var noiseFloor = 0f

    private fun effectiveSpeechRms(): Float =
        speechRms + noiseFloor.coerceAtMost(speechRms * (noiseAdaptMaxFactor - 1f))

    /** 供 UI/诊断观察当前有效阈值 */
    val currentThreshold: Float get() = effectiveSpeechRms()

    /** 供 UI 显示「正在说话」 */
    val isSpeaking: Boolean get() = inSpeech

    fun reset() {
        buf.clear()
        silentRunMs = 0
        speechMs = 0
        inSpeech = false
        noiseFloor = 0f
    }

    /**
     * 喂一块音频。
     * @return 若一句结束则返回完整段的样本，否则 null
     */
    fun feed(chunk: FloatArray): FloatArray? {
        if (chunk.isEmpty()) return null

        val chunkMs = chunk.size * 1000 / sampleRate
        val rms = rms(chunk)

        // 噪声自适应：仅在已进入说话态（用户真实开口）时抬基线，
        // 防止底噪从静止状态缓慢抬高阈值。说话中 rms 持续偏高说明
        // 可能一直在说话（保护长录音能切段）；一旦停顿立即停止抬升。
        if (inSpeech && rms >= effectiveSpeechRms()) {
            noiseFloor += noiseAdaptRatePerSec * chunkMs / 1000f
        }

        if (rms >= effectiveSpeechRms()) {
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
        if (!speechEnough || out.isEmpty()) return null
        // 头部裁剪：段首常混入触发前的环境噪声（VAD 从按下按钮就 inSpeech），
        // 长噪声开头会让 Whisper 幻觉出重复文本。向前裁掉连续低能量前缀，
        // 保留 150ms 余量防裁到句首辅音。
        val leadKeep = sampleRate * 150 / 1000
        var head = 0
        var runMs = 0
        while (head < out.size - leadKeep) {
            val isLoud = runLoudness(out, head, chunkMs = 50) >= speechRms
            if (isLoud) break
            head += sampleRate * 50 / 1000
            runMs += 50
        }
        val trimmed = if (head > 0) out.copyOfRange(head, out.size) else out
        return if (trimmed.size >= sampleRate * minSpeechMs / 1000) trimmed else null
    }

    /** 计算 from 开始 chunkMs 窗口的 RMS */
    private fun runLoudness(a: FloatArray, from: Int, chunkMs: Int): Float {
        val n = minOf(sampleRate * chunkMs / 1000, a.size - from)
        if (n <= 0) return 0f
        var s = 0.0
        for (i in from until from + n) s += a[i].toDouble() * a[i]
        return kotlin.math.sqrt(s / n).toFloat()
    }

    private fun rms(a: FloatArray): Float {
        var sum = 0.0
        for (v in a) sum += v.toDouble() * v
        return sqrt(sum / a.size).toFloat()
    }
}
