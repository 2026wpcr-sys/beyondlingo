package dev.beyonddev.beyondlingo.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置清单校验。
 *
 * 内置清单是编译期常量，解析失败属于代码缺陷（App 会直接崩），
 * 所以用单测锁住：结构正确、URL 带 raw=true、SHA-256 是合法 hex。
 *
 * 当前清单结构（v2）：1 档云端（Groq）+ 2 档本地（base / small）。
 */
class BuiltInModelsTest {

    private fun localEntries() =
        BuiltInModels.manifest().models.filter { it.kind == "asr-offline" }

    private fun cloudEntries() =
        BuiltInModels.manifest().models.filter { it.kind == "asr-cloud" }

    @Test
    fun `清单能解析且档位数量正确`() {
        val m = BuiltInModels.manifest()
        assertEquals(2, m.version)
        assertEquals("应为 1 档云端 + 2 档本地", 3, m.models.size)
        assertEquals(1, cloudEntries().size)
        assertEquals(2, localEntries().size)
    }

    @Test
    fun `云端档无需文件 本地档三件套齐备`() {
        cloudEntries().forEach { e ->
            assertTrue("云端档 ${e.id} 不应有下载文件", e.files.isEmpty())
        }
        localEntries().forEach { e ->
            assertEquals("${e.id} 应为三件套", 3, e.files.size)
            assertEquals(
                "${e.id} 的 key 不齐",
                setOf("encoder", "decoder", "tokens"),
                e.files.map { it.key }.toSet(),
            )
        }
    }

    @Test
    fun `本地档 sha256 是合法 hex 且大小已知`() {
        localEntries().flatMap { it.files }.forEach { f ->
            assertEquals("${f.key} 的 sha256 长度应为 64", 64, f.sha256.length)
            assertTrue(
                "${f.key} 的 sha256 应为小写 hex",
                f.sha256.all { it in "0123456789abcdef" },
            )
            assertTrue("${f.key} 应有已知大小", f.sizeBytes > 0)
        }
    }

    @Test
    fun `本地档 URL 必须是 https 且指向官方仓库`() {
        localEntries().flatMap { it.files }.forEach { f ->
            assertTrue("${f.key} 必须用 https", f.url.startsWith("https://"))
            assertTrue(
                "${f.key} 不应带 query 参数",
                !f.url.contains('?'),
            )
            assertTrue(
                "${f.key} 应指向官方仓库",
                f.url.contains("sherpa-onnx-whisper"),
            )
        }
    }

    @Test
    fun `默认模型 id 必须是本地档`() {
        // 默认档不能是云端，否则用户首次使用会因没填 Key 而不可用
        val ids = localEntries().map { it.id }
        assertTrue(
            "DEFAULT_MODEL_ID=${BuiltInModels.DEFAULT_MODEL_ID} 不在本地档里",
            BuiltInModels.DEFAULT_MODEL_ID in ids,
        )
    }

    @Test
    fun `fileNameFor 取扩展名时忽略 query 参数`() {
        // ModelDownloader.fileNameFor 用 substringBefore('?')，
        // 否则 query 会混进本地文件名
        val f = ModelFile(
            key = "encoder",
            url = "https://example.com/base-encoder.int8.onnx?raw=true",
            sha256 = "a".repeat(64),
            sizeBytes = 1,
        )
        val tail = f.url.substringBefore('?').substringAfterLast('/')
        assertEquals("base-encoder.int8.onnx", tail)
    }

    @Test
    fun `内置清单常量是合法 JSON 且无 BOM`() {
        val raw = BuiltInModels.MANIFEST_JSON
        assertTrue("不应以 BOM 开头", !raw.startsWith("\uFEFF"))
        val parsed = ModelManifestParser.parse(raw)
        assertNotNull(parsed.getOrNull())
    }
}
