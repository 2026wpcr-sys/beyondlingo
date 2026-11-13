package dev.beyonddev.beyondlingo.data.document

import android.content.Context
import android.util.Log
import dev.beyonddev.beyondlingo.data.TranslateRepository
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.data.engine.TranslateOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文档翻译：解析 → 按块翻译 → 回填结构 → 导出。
 *
 * 分段策略（见 wayfinder/document-parsing.md「决策 5」）：
 * **以「块」为翻译单元**，块 ↔ 译文一一映射，回填零歧义。
 * 绝不让模型输出整篇 markdown 再解析回来（块数对不上就整篇崩）。
 *
 * 长度按 codePointCount 计（emoji 在 UTF-16 占 2）。
 */
class DocumentTranslator(private val context: Context) {

    private companion object {
        const val TAG = "DocumentTranslator"
        const val MAX_CHARS_PER_BLOCK = 3000
    }

    data class Result(
        val blocks: List<Block>,
        val translations: Map<Int, String>,
        val failedCount: Int,
    )

    /** 预计翻译字符数（用于二次确认） */
    fun estimate(blocks: List<Block>): Int =
        blocks.filter { shouldTranslate(it) }
            .sumOf { it.text.codePointCount(0, it.text.length) }

    private fun shouldTranslate(b: Block): Boolean {
        if (b.text.isBlank()) return false
        if (b.type == BlockType.CODE) return false
        // 纯数字/标点/URL 不送翻译
        val hasWord = b.text.any { it.isLetter() }
        if (!hasWord) return false
        // 极短（1 个字符且非汉字/字母）
        if (b.text.codePointCount(0, b.text.length) < 2) return false
        return true
    }

    suspend fun translate(
        file: File,
        displayName: String,
        targetLang: LangEnum,
        engineId: String,
        engineConfig: EngineConfig,
        progress: (String, Int) -> Unit = { _, _ -> },
    ): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        runCatching {
            progress("解析文档…", 2)
            val blocks = DocumentParser.parse(file, displayName)
            if (blocks.isEmpty()) error("文档没有可翻译的文本")

            val targets = blocks.filter { shouldTranslate(it) }
            val repo = TranslateRepository(context)
            val out = HashMap<Int, String>(targets.size)
            var failed = 0

            targets.forEachIndexed { idx, block ->
                val pct = 5 + (idx * 90 / targets.size.coerceAtLeast(1))
                progress("翻译 ${idx + 1}/${targets.size}…", pct)

                // 超长块按句子粗切，保证不超过单请求上限
                val pieces = splitIfNeeded(block.text)
                val sb = StringBuilder()
                var pieceFailed = false

                for (piece in pieces) {
                    val outcome = repo.translate(
                        text = piece,
                        source = LangEnum.Auto,
                        target = targetLang,
                        engineId = engineId,
                        config = engineConfig,
                    )
                    when (outcome) {
                        is TranslateOutcome.Success -> sb.append(outcome.text)
                        is TranslateOutcome.Failure -> {
                            pieceFailed = true
                            Log.w(TAG, "block ${block.id} failed: ${outcome.message}")
                            break
                        }
                    }
                }

                if (pieceFailed) failed++ else out[block.id] = sb.toString()
            }

            progress("完成", 100)
            Result(blocks = blocks, translations = out, failedCount = failed)
        }
    }

    private fun splitIfNeeded(text: String): List<String> {
        if (text.codePointCount(0, text.length) <= MAX_CHARS_PER_BLOCK) {
            return listOf(text)
        }
        val out = ArrayList<String>()
        var buf = StringBuilder()
        // 按中英文句末标点切，尽量保持语义完整
        val boundaries = Regex("(?<=[。！？!?；;.])\\s*")
        val sentences = text.split(boundaries)
        for (s in sentences) {
            if (buf.length + s.length > MAX_CHARS_PER_BLOCK) {
                if (buf.isNotEmpty()) {
                    out.add(buf.toString())
                    buf = StringBuilder()
                }
                if (s.length > MAX_CHARS_PER_BLOCK) {
                    // 单句仍超长：硬切
                    var i = 0
                    while (i < s.length) {
                        val end = minOf(i + MAX_CHARS_PER_BLOCK, s.length)
                        out.add(s.substring(i, end))
                        i = end
                    }
                } else {
                    buf.append(s)
                }
            } else {
                buf.append(s)
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }

    /** 导出：把译文按原结构输出为 Markdown（能表达标题层级/列表，最省事） */
    fun renderMarkdown(blocks: List<Block>, translations: Map<Int, String>, bilingual: Boolean): String {
        val sb = StringBuilder()
        blocks.forEach { b ->
            val t = translations[b.id]
            val src = b.text
            when (b.type) {
                BlockType.HEADING -> {
                    sb.append("#".repeat(b.level.coerceIn(1, 6))).append(' ')
                    sb.append(t ?: src).append('\n')
                    if (bilingual && t != null) sb.append('\n').append(src).append('\n')
                    sb.append('\n')
                }

                BlockType.LIST_ITEM -> {
                    val marker = b.listMarker.ifEmpty { "-" }
                    sb.append(marker).append(' ').append(t ?: src).append('\n')
                    if (bilingual && t != null) sb.append("  ").append(src).append('\n')
                }

                BlockType.QUOTE -> {
                    sb.append("> ").append(t ?: src).append('\n')
                    if (bilingual && t != null) sb.append("> ").append(src).append('\n')
                    sb.append('\n')
                }

                BlockType.TABLE_CELL -> {
                    sb.append(t ?: src).append(" | ")
                    if (bilingual && t != null) sb.append(src).append(" | ")
                }

                else -> {
                    sb.append(t ?: src).append('\n')
                    if (bilingual && t != null) sb.append(src).append('\n')
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    fun renderPlain(blocks: List<Block>, translations: Map<Int, String>, bilingual: Boolean): String {
        val sb = StringBuilder()
        blocks.forEach { b ->
            val t = translations[b.id]
            sb.append(t ?: b.text).append('\n')
            if (bilingual && t != null) sb.append(b.text).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }
}
