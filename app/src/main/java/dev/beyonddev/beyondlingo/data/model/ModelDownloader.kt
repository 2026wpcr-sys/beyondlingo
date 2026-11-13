package dev.beyonddev.beyondlingo.data.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 模型下载器：断点续传 + SHA-256 校验 + 版本标记。
 *
 * 落盘结构：
 * ```
 * filesDir/models/<modelId>/
 *   ├─ <key>.<ext>       实际模型文件（文件名由 [fileNameFor] 决定）
 *   └─ .done             校验通过后写入的标记
 * ```
 *
 * 断点续传：对每个文件先检查本地已下载字节数，带 `Range: bytes=N-` 续传；
 * 服务器返回 200（不支持 Range）时从头覆盖。
 */
class ModelDownloader(context: Context) {

    private companion object {
        const val TAG = "ModelDownloader"
        const val DONE_MARKER = ".done"

        /** 清单来源标记文件；与 manifest.json 同级 */
        const val SOURCE_MARKER = "manifest.source"

        /** 来源：用户填写的云空间地址 */
        const val SOURCE_USER = "user"

        /** 来源：App 内置清单 */
        const val SOURCE_BUILTIN = "builtin"
    }

    private val root = File(context.filesDir, "models").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 某个模型是否已完整就绪 */
    fun isReady(entry: ModelEntry): Boolean =
        File(dirOf(entry.id), DONE_MARKER).exists()

    fun dirOf(modelId: String): File = File(root, modelId)

    /** 返回某用途对应的绝对路径；未就绪时返回 null */
    fun pathOf(entry: ModelEntry, key: String): String? {
        if (!isReady(entry)) return null
        val f = entry.files.firstOrNull { it.key == key } ?: return null
        return File(dirOf(entry.id), fileNameFor(f)).absolutePath
    }

    private fun fileNameFor(f: ModelFile): String {
        // 保留 URL 里的扩展名（.onnx / .txt），避免模型加载器按扩展名判断失败
        val tail = f.url.substringBefore('?').substringAfterLast('/')
        val ext = tail.substringAfterLast('.', "")
        return if (ext.isBlank() || ext.length > 8) "${f.key}.bin" else "${f.key}.$ext"
    }

    sealed interface Progress {
        data class Downloading(
            val modelId: String,
            val fileKey: String,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : Progress

        data class Verifying(val modelId: String) : Progress
        data class Done(val modelId: String) : Progress
        data class Failed(val modelId: String, val message: String) : Progress
    }

    /**
     * 下载模型文件（断点续传 + SHA-256 校验）。
     *
     * 末尾的 flowOn(Dispatchers.IO) 保证同步的 OkHttp execute() 不在主线程执行
     * （真机实测：跑在主线程会抛 NetworkOnMainThreadException）。
     */
    fun download(entry: ModelEntry): Flow<Progress> = flow {
        val dir = dirOf(entry.id).apply { mkdirs() }
        try {
            for (mf in entry.files) {
                val dest = File(dir, fileNameFor(mf))
                if (dest.exists() && mf.sizeBytes > 0 && dest.length() == mf.sizeBytes) {
                    // 长度吻合，跳过下载（稍后统一校验）
                    emit(
                        Progress.Downloading(entry.id, mf.key, dest.length(), mf.sizeBytes)
                    )
                    continue
                }

                val existing = if (dest.exists()) dest.length() else 0L
                val reqBuilder = Request.Builder().url(mf.url)
                if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")

                val response = client.newCall(reqBuilder.get().build()).execute()
                if (!response.isSuccessful) {
                    emit(Progress.Failed(entry.id, "HTTP ${response.code} @ ${mf.key}"))
                    return@flow
                }

                val append = response.code == 206 // Partial Content
                val body = response.body ?: run {
                    emit(Progress.Failed(entry.id, "empty body @ ${mf.key}"))
                    return@flow
                }
                val total = if (mf.sizeBytes > 0) mf.sizeBytes else body.contentLength()
                var written = if (append) existing else 0L

                FileOutputStream(dest, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            written += n
                            emit(Progress.Downloading(entry.id, mf.key, written, total))
                        }
                    }
                }
                if (!append && existing > 0 && written < existing) {
                    // 服务器忽略了 Range 且返回内容更短，交给后续校验兜底
                    Log.w(TAG, "server ignored Range for ${mf.key}")
                }
            }

            emit(Progress.Verifying(entry.id))

            // 统一校验
            for (mf in entry.files) {
                val f = File(dir, fileNameFor(mf))
                if (!f.exists()) {
                    emit(Progress.Failed(entry.id, "missing ${mf.key}"))
                    return@flow
                }
                val actual = sha256(f)
                if (!actual.equals(mf.sha256, ignoreCase = true)) {
                    f.delete()
                    emit(
                        Progress.Failed(
                            entry.id,
                            "SHA-256 mismatch @ ${mf.key}: $actual"
                        )
                    )
                    return@flow
                }
            }

            File(dir, DONE_MARKER).writeText("v1")
            emit(Progress.Done(entry.id))
        } catch (t: Throwable) {
            emit(Progress.Failed(entry.id, t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 拉取云端 manifest，解析成功后落盘一份，供离线时读取。
     *
     * 必须是 suspend：OkHttp 的同步 execute() 跑在主线程会抛
     * NetworkOnMainThreadException（真机实测）。调用方通常是 viewModelScope
     * （Main 调度器），所以这里自己切到 IO。
     */
    suspend fun fetchManifest(url: String): Result<ModelManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("empty manifest")
                val manifest = ModelManifestParser.parse(body).getOrThrow()
                root.mkdirs()
                // 落盘时去掉 BOM：本地缓存要被 org.json 直接解析
                File(root, "manifest.json")
                    .writeText(body.trim().removePrefix("\uFEFF").trim())
                // 标记来源：用户显式配置的地址，优先级高于内置清单
                markUserSource()
                manifest
            }
        }
    }

    /**
     * 读取可用清单。
     *
     * 来源优先级（真机踩过的坑）：
     * 1. 缓存标记为 `user` → 用它（用户显式填了云空间地址，用户的显式选择优先）
     * 2. 缓存标记为 `builtin` → 用它
     * 3. 缓存**没有标记** → 视为历史遗留（早期 debug 测试时落盘的清单，
     *    可能指向旧模型如 whisper-tiny），**用内置清单覆盖**
     * 4. 完全没有缓存 → 用内置清单，并落盘（保证后续流程与用户地址同构）
     *
     * 第 3 条是关键：不加这个判断，装了 release 版但设备上留有 debug 测试
     * 缓存时，会加载到旧模型而不是内置的新模型。
     */
    fun cachedManifest(): ModelManifest? {
        val cachedFile = File(root, "manifest.json")
        val sourceFile = File(root, SOURCE_MARKER)
        val source = runCatching { sourceFile.readText().trim() }.getOrNull()

        if (cachedFile.exists() && source == SOURCE_USER) {
            runCatching { ModelManifestParser.parse(cachedFile.readText()).getOrNull() }
                .getOrNull()?.let { return it }
        }
        return persistBuiltInManifest()
    }

    /**
     * 把内置清单落盘，让后续流程与「用户填地址拉取」完全同构
     * （modelId 目录、.done 标记、isReady 判定都依赖同一套路径规则）。
     */
    fun persistBuiltInManifest(): ModelManifest? = runCatching {
        val m = BuiltInModels.manifest()
        root.mkdirs()
        File(root, "manifest.json").writeText(BuiltInModels.MANIFEST_JSON.trim())
        File(root, SOURCE_MARKER).writeText(SOURCE_BUILTIN)
        m
    }.getOrNull()

    /** 记录清单来源为「用户地址」 */
    private fun markUserSource() {
        runCatching {
            root.mkdirs()
            File(root, SOURCE_MARKER).writeText(SOURCE_USER)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
