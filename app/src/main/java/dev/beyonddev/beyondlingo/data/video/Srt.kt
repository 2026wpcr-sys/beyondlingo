package dev.beyonddev.beyondlingo.data.video

import java.io.File

/**
 * 一条字幕。
 *
 * 双语实现的关键（见 wayfinder/subtitle-output-form.md）：
 * 把原文和译文合并进**同一条字幕的文本**里（两行），
 * 标准 SRT 渲染天然显示两行，不需要自定义渲染器。
 */
data class Cue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val original: String,
    val translated: String?,
    val failed: Boolean = false,
)

object Srt {

    /** 毫秒 → SRT 时间戳 `HH:MM:SS,mmm` */
    fun timestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1000
        val milli = total % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    /** 生成双语 SRT 文本（原文行 + 译文行） */
    fun render(cues: List<Cue>, bilingual: Boolean = true): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, cue ->
            val end = if (cue.endMs > cue.startMs) cue.endMs else cue.startMs + 800
            sb.append(i + 1).append('\n')
            sb.append(timestamp(cue.startMs)).append(" --> ").append(timestamp(end)).append('\n')
            sb.append(cue.original.trim()).append('\n')
            if (bilingual) {
                val t = cue.translated?.trim()
                if (!t.isNullOrEmpty()) sb.append(t).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun write(cues: List<Cue>, file: File, bilingual: Boolean = true) {
        file.writeText(render(cues, bilingual))
    }
}
