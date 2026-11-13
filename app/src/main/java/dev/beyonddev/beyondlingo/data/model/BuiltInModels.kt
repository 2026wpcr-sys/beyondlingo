package dev.beyonddev.beyondlingo.data.model

/**
 * 内置模型清单（随 App 发布，不需要额外下载 manifest）。
 *
 * 背景：Kaggle 的数据集**不允许混用数据源**（Remote Files 与本地上传二选一，
 * 见 Kaggle 官方文档「a dataset can be created and versioned from exclusively
 * one data source」），所以模型走 Remote Files 导入时，manifest.json 就没法
 * 放进同一个数据集。
 *
 * 解决办法：manifest 直接内置成代码常量。用户装完 App 就有可用的模型清单，
 * 在「模型」页仍可改成自己的地址（覆盖内置值）。
 *
 * 云空间要求（实测自 HuggingFace / Kaggle）：
 * - HTTPS（App 的 networkSecurityConfig 全局强制 HTTPS）
 * - 支持 HTTP Range（断点续传）
 * - 单文件直链，不能是下载页
 *
 * URL 参数说明：
 * - Kaggle 端点必须带 `?raw=true`，否则返回临时 zip 包裹；
 *   带 raw=true 走 `kagglesdsdata` 原始存储路径，直接拿到原样文件。
 * - `fileNameFor` 会用 `substringBefore('?')` 去掉 query，避免文件名带上参数。
 */
object BuiltInModels {

    /**
     * 模型文件的直链前缀。
     *
     * 当前用 HuggingFace 官方仓库 `csukuangfj/sherpa-onnx-whisper-base`
     * （k2-fsa 作者本人发布，多语种，含 int8 encoder/decoder + tokens.txt）。
     *
     * 实测（真机 + curl）：
     * - `Range: bytes=0-99` → 206 Partial Content
     * - `Accept-Ranges: bytes`、有 Content-Length（进度条可算百分比）
     * - 29MB 文件 2.1 秒拉完
     * - 三个文件的 SHA-256 与官方 GitHub Release
     *   `sherpa-onnx-whisper-base.tar.bz2` 完全一致（逐个比对过）
     *
     * 若要换成 Kaggle 分发，把这里改成
     * `https://www.kaggle.com/api/v1/datasets/download/<owner>/<slug>`，
     * 并给每个文件 URL 追加 `?raw=true`（否则 Kaggle 会返回 compressed/xxx.zip
     * 临时包裹，不是原样文件）。
     */
    private const val MODEL_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main"

    /**
     * 内置 manifest 的 JSON。
     *
     * 指向 `csukuangfj/sherpa-onnx-whisper-base`（k2-fsa 作者官方 HF 仓库）
     * 的 Whisper base int8。多语种（中/英/西），自带 lang 语言检测 ——
     * 双向同传的方向路由依赖它。
     *
     * SHA-256 与官方 Release 的 `sherpa-onnx-whisper-base.tar.bz2` 一致（已实测比对）。
     *
     * 注意用普通 `val` 而不是 `const val`：字符串模板里插值另一个常量
     * 不是编译期常量表达式，const 不允许。
     */
    val MANIFEST_JSON: String = """
{
  "version": 1,
  "models": [
    {
      "id": "whisper-base-int8",
      "name": "Whisper base int8 多语种（中/英/西）",
      "kind": "asr-offline",
      "files": [
        {
          "key": "encoder",
          "url": "${MODEL_BASE}/base-encoder.int8.onnx",
          "sha256": "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
          "sizeBytes": 29120534
        },
        {
          "key": "decoder",
          "url": "${MODEL_BASE}/base-decoder.int8.onnx",
          "sha256": "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
          "sizeBytes": 130672026
        },
        {
          "key": "tokens",
          "url": "${MODEL_BASE}/base-tokens.txt",
          "sha256": "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
          "sizeBytes": 816730
        }
      ]
    }
  ]
}
"""

    /** 直接得到解析好的内置清单 */
    fun manifest(): ModelManifest =
        ModelManifestParser.parse(MANIFEST_JSON).getOrElse {
            // 内置常量是编译期写死的，解析失败属于代码缺陷，必须暴露出来
            throw IllegalStateException("内置 manifest 解析失败: ${it.message}", it)
        }
}
