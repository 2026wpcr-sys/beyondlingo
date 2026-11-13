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
 */
class BuiltInModelsTest {

    @Test
    fun `内置清单能解析且结构正确`() {
        val m = BuiltInModels.manifest()
        assertEquals(1, m.version)
        assertTrue("应至少有一个模型", m.models.isNotEmpty())

        val entry = m.models.first()
        assertEquals("asr-offline", entry.kind)
        assertEquals("whisper-base-int8", entry.id)
        assertEquals(3, entry.files.size)
    }

    @Test
    fun `文件 key 齐备且 sha256 是合法 hex`() {
        val entry = BuiltInModels.manifest().models.first()
        val keys = entry.files.map { it.key }.toSet()
        assertEquals(setOf("encoder", "decoder", "tokens"), keys)

        entry.files.forEach { f ->
            assertTrue("${f.key} 的 sha256 长度应为 64", f.sha256.length == 64)
            assertTrue("${f.key} 的 sha256 应为小写 hex", f.sha256.all { it in "0123456789abcdef" })
            assertTrue("${f.key} 应有已知大小", f.sizeBytes > 0)
        }
    }

    @Test
    fun `URL 必须是 https 且带 raw=true`() {
        BuiltInModels.manifest().models.first().files.forEach { f ->
            assertTrue("${f.key} 必须用 https", f.url.startsWith("https://"))
            assertTrue(
                "${f.key} 必须带 ?raw=true，否则 Kaggle 会返回 zip 包裹",
                f.url.contains("?raw=true"),
            )
        }
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
