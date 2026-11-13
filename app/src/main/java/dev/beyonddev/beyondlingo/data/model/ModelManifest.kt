package dev.beyonddev.beyondlingo.data.model

import org.json.JSONObject

/**
 * 模型清单（放在云空间根目录下）。
 *
 * APK 只内置最小可用模型，其余按 manifest 按需下载到 filesDir/models/<id>/。
 * 每个模型文件是**独立直链**（不做压缩包解压，避免 Android 上 bz2 依赖），
 * 下载完逐个 SHA-256 校验，全部通过才写入 done 标记。
 *
 * 云空间要求：HTTPS 直链 + 支持 HTTP Range。网盘类（需登录/限速）不适合。
 *
 * 用 org.json 手写解析，零新增依赖。
 */
data class ModelFile(
    /** 用途键：encoder / decoder / joiner / tokens / model / ... */
    val key: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class ModelEntry(
    val id: String,
    val name: String,
    /** asr / punctuation / tts */
    val kind: String,
    val files: List<ModelFile>,
)

data class ModelManifest(
    val version: Int,
    val models: List<ModelEntry>,
)

object ModelManifestParser {

    fun parse(text: String): Result<ModelManifest> = runCatching {
        // 容忍 UTF-8 BOM：Windows 记事本 / PowerShell Set-Content 存出的 JSON 常带 BOM，
        // org.json 遇到 BOM 会直接解析失败（且异常 message 可能为 null，极难排查）。
        val clean = text.trim().removePrefix("\uFEFF").trim()
        require(clean.startsWith("{")) {
            "manifest 不是合法 JSON 对象（首字符 '${clean.take(1)}'）"
        }
        val root = JSONObject(clean)
        val version = root.optInt("version", 1)
        val arr = root.getJSONArray("models")
        val list = ArrayList<ModelEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fa = o.getJSONArray("files")
            val files = ArrayList<ModelFile>(fa.length())
            for (j in 0 until fa.length()) {
                val f = fa.getJSONObject(j)
                files.add(
                    ModelFile(
                        key = f.getString("key"),
                        url = f.getString("url"),
                        sha256 = f.getString("sha256").lowercase(),
                        sizeBytes = f.optLong("sizeBytes", 0L),
                    )
                )
            }
            list.add(
                ModelEntry(
                    id = o.getString("id"),
                    name = o.optString("name", o.getString("id")),
                    kind = o.optString("kind", "asr"),
                    files = files,
                )
            )
        }
        ModelManifest(version, list)
    }
}
