package dev.beyonddev.beyondlingo.data.document

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * 文档解析：把不同格式统一成「块序列」。
 *
 * MVP 只做 txt / md / docx（见 wayfinder/document-parsing.md）：
 * - docx 用 java.util.zip + XmlPullParser 自研，**不引 Apache POI**
 *   （POI 依赖 java.awt，Android 上会 NoClassDefFoundError，且 12MB+）
 * - 体积增量 ≈ 0
 */
enum class BlockType { PARAGRAPH, HEADING, LIST_ITEM, TABLE_CELL, CODE, QUOTE }

data class Block(
    val id: Int,
    val type: BlockType,
    val text: String,
    val level: Int = 0,
    val listMarker: String = "",
)

object DocumentParser {

    /** 解析入口：按扩展名分派 */
    fun parse(file: File, displayName: String): List<Block> {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "text" -> parsePlain(TextDecoder.decode(file))
            "md", "markdown" -> parseMarkdown(TextDecoder.decode(file))
            "docx" -> parseDocx(file)
            else -> error("不支持的格式 .$ext（当前支持 txt / md / docx）")
        }
    }

    // ---- 纯文本 ----

    private fun parsePlain(text: String): List<Block> {
        val out = ArrayList<Block>()
        var id = 0
        text.split(Regex("\\r?\\n")).forEach { line ->
            val t = line.trim()
            if (t.isNotEmpty()) out.add(Block(id++, BlockType.PARAGRAPH, t))
        }
        return out
    }

    // ---- Markdown ----

    private fun parseMarkdown(text: String): List<Block> {
        val out = ArrayList<Block>()
        var id = 0
        var inCode = false

        text.split(Regex("\\r?\\n")).forEach { line ->
            val t = line.trim()

            if (t.startsWith("```")) {
                inCode = !inCode
                return@forEach
            }
            if (inCode) {
                // 代码块整块跳过不翻译
                return@forEach
            }
            if (t.isEmpty()) return@forEach

            when {
                Regex("^#{1,6}\\s+").containsMatchIn(line) -> {
                    val level = t.takeWhile { it == '#' }.length
                    out.add(
                        Block(
                            id++, BlockType.HEADING,
                            t.dropWhile { it == '#' }.trim(), level
                        )
                    )
                }

                Regex("^\\s*([-*+]|\\d+\\.)\\s+").containsMatchIn(line) -> {
                    val marker = Regex("^\\s*([-*+]|\\d+\\.)\\s+")
                        .find(line)?.groupValues?.get(1) ?: "-"
                    out.add(
                        Block(
                            id++, BlockType.LIST_ITEM,
                            t.removePrefix(marker).trim(), 0, marker
                        )
                    )
                }

                t.startsWith(">") ->
                    out.add(Block(id++, BlockType.QUOTE, t.removePrefix(">").trim()))

                else ->
                    out.add(Block(id++, BlockType.PARAGRAPH, t))
            }
        }
        return out
    }

    // ---- docx（ZIP + OOXML）----

    private fun parseDocx(file: File): List<Block> {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("docx 缺少 word/document.xml")
            zip.getInputStream(entry).use { input ->
                return readDocumentXml(input)
            }
        }
    }

    private fun readDocumentXml(input: java.io.InputStream): List<Block> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, "UTF-8")
        }

        val out = ArrayList<Block>()
        var id = 0

        var inParagraph = false
        var inText = false
        var inTable = false
        var inCell = false
        var inDeleted = false   // w:delText —— 修订删除内容，必须丢弃
        var inInstr = false     // w:instrText —— 域代码，必须丢弃
        var inSdt = false       // w:sdt —— 内容控件，递归进入
        var style = ""
        var outline = -1
        val sb = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name ?: ""

            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "p" -> {
                        inParagraph = true
                        sb.setLength(0)
                        style = ""
                        outline = -1
                    }

                    name == "pStyle" -> style = parser.getAttributeValue(null, "w:val") ?: ""
                    name == "outlineLvl" ->
                        outline = parser.getAttributeValue(null, "w:val")?.toIntOrNull() ?: -1

                    name == "tbl" -> inTable = true
                    name == "tc" -> inCell = true
                    name == "sdt" -> inSdt = true
                    name == "delText" -> inDeleted = true
                    name == "instrText" -> inInstr = true
                    name == "t" -> inText = true
                    name == "br" || name == "cr" -> if (inParagraph) sb.append(' ')
                }

                XmlPullParser.TEXT -> {
                    if (inText && !inDeleted && !inInstr) {
                        sb.append(parser.text ?: "")
                    }
                }

                XmlPullParser.END_TAG -> when (name) {
                    "t" -> inText = false
                    "delText" -> inDeleted = false
                    "instrText" -> inInstr = false
                    "sdt" -> inSdt = false
                    "tc" -> inCell = false
                    "tbl" -> inTable = false
                    "p" -> {
                        inParagraph = false
                        val text = sb.toString().trim()
                        if (text.isNotEmpty()) {
                            val isHeading = outline in 0..8 ||
                                style.startsWith("Heading", true) ||
                                style.startsWith("标题")
                            val type = when {
                                inCell -> BlockType.TABLE_CELL
                                isHeading -> BlockType.HEADING
                                else -> BlockType.PARAGRAPH
                            }
                            val level = if (outline >= 0) outline + 1 else 0
                            out.add(Block(id++, type, text, level))
                        }
                        sb.setLength(0)
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
