package dev.beyonddev.beyondlingo.data.video

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import dev.beyonddev.beyondlingo.data.TranslateRepository
import dev.beyonddev.beyondlingo.data.asr.AsrEngine
import dev.beyonddev.beyondlingo.data.asr.AsrUpdate
import dev.beyonddev.beyondlingo.data.asr.PunctuationEngine
import dev.beyonddev.beyondlingo.data.asr.WavUtil
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.data.engine.TranslateOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地视频翻译：抽音频 → 分段识别 → 逐段翻译 → 双语字幕。
 *
 * 设计约束（见 WAYFINDER 决策）：
 * - 视频**只读不重编码**，FFmpeg 仅用于抽 16k 单声道 wav
 * - 输出是「App 内播放 + 导出 .srt」，不做硬字幕烧录
 * - 双语靠同一条字幕内两行文本实现，不用双字幕轨
 *
 * 时间轴说明（近似）：SRT 时间按「累计喂入样本数」推算，
 * 端点检测的静音尾部会让上界略偏后，属可接受的近似；
 * 精确到 token 级需要 sherpa-onnx 的时间戳输出，当前未启用。
 */
class VideoTranslator(private val context: Context) {

    private companion object {
        const val TAG = "VideoTranslator"
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1600 // 100ms
    }

    data class Result(
        val cues: List<Cue>,
        val srtFile: File?,
        val audioMs: Long,
    )

    /**
     * @param progress (阶段描述, 0..100)
     */
    suspend fun translate(
        videoUri: Uri,
        targetLang: LangEnum,
        engineId: String,
        engineConfig: dev.beyonddev.beyondlingo.data.engine.EngineConfig,
        progress: (String, Int) -> Unit = { _, _ -> },
    ): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        runCatching {
            val work = File(context.cacheDir, "video-translate").apply { mkdirs() }
            val wav = File(work, "audio.wav")
            if (wav.exists()) wav.delete()

            // 1) SAF 的 content:// 不一定可 seek，先拷到私有目录
            progress("复制视频…", 2)
            val src = File(work, "source.bin")
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                src.outputStream().use { out -> input.copyTo(out) }
            } ?: error("无法读取所选视频")

            // 2) 抽音频：16k / 单声道 / pcm_s16le
            progress("提取音频…", 10)
            val cmd = "-y -i \"${src.absolutePath}\" -vn -ac 1 -ar $SAMPLE_RATE -f wav \"${wav.absolutePath}\""
            val session = FFmpegKit.execute(cmd)
            val rc = session.returnCode
            if (rc == null || !rc.isValueSuccess) {
                error("音频提取失败：${session.failStackTrace ?: rc}")
            }
            if (!wav.exists() || wav.length() < 1024) error("音频为空")

            // 3) 识别
            progress("语音识别…", 20)
            val samples = WavUtil.readFromFile(wav)
            val audioMs = samples.size * 1000L / SAMPLE_RATE

            val asr = AsrEngine(context)
            if (!asr.initialize()) error("ASR 模型加载失败")

            val punct = PunctuationEngine(context)
            // 标点模型由调用方通过 manifest 提供；此处不额外加载，保持独立

            val rawSegments = ArrayList<Triple<Long, Long, String>>()
            var fed = 0L
            var segStartMs = 0L
            var i = 0

            while (i < samples.size) {
                val end = minOf(i + CHUNK, samples.size)
                val upd = asr.accept(samples.copyOfRange(i, end))
                fed += (end - i)
                if (upd is AsrUpdate.Final && upd.text.isNotBlank()) {
                    val endMs = fed * 1000L / SAMPLE_RATE
                    rawSegments.add(Triple(segStartMs, endMs, upd.text))
                    segStartMs = endMs
                }
                i = end
                if (i % (CHUNK * 50) == 0) {
                    progress("语音识别…", 20 + (i * 50 / samples.size))
                }
            }
            asr.flush()?.let { f ->
                if (f.text.isNotBlank()) {
                    rawSegments.add(Triple(segStartMs, audioMs, f.text))
                }
            }
            asr.release()
            punct.release()

            if (rawSegments.isEmpty()) error("没有识别到语音内容")

            // 4) 翻译
            val repo = TranslateRepository(context)
            val cues = ArrayList<Cue>(rawSegments.size)
            rawSegments.forEachIndexed { idx, (start, end, text) ->
                val pct = 70 + (idx * 25 / rawSegments.size.coerceAtLeast(1))
                progress("翻译 ${idx + 1}/${rawSegments.size}…", pct)
                val outcome = repo.translate(
                    text = text,
                    source = LangEnum.Auto,
                    target = targetLang,
                    engineId = engineId,
                    config = engineConfig,
                    checkCacheFirst = true,
                )
                val (t, failed) = when (outcome) {
                    is TranslateOutcome.Success -> outcome.text to false
                    is TranslateOutcome.Failure -> null to true
                }
                cues.add(
                    Cue(
                        index = idx,
                        startMs = start,
                        endMs = end,
                        original = text,
                        translated = t,
                        failed = failed,
                    )
                )
            }

            // 5) 写 SRT
            progress("生成字幕…", 97)
            val srt = File(work, "translated.srt")
            Srt.write(cues, srt, bilingual = true)

            runCatching { src.delete() }
            progress("完成", 100)
            Result(cues = cues, srtFile = srt, audioMs = audioMs)
        }
    }
}
