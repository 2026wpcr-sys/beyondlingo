package dev.beyonddev.beyondlingo.data.model

/**
 * 内置模型清单（随 App 发布，不需要额外下载 manifest）。
 *
 * 背景：Kaggle 的数据集不允许混用数据源（Remote Files 与本地上传二选一），
 * 所以模型走 Remote Files 导入时 manifest.json 没法放进同一个数据集。
 * 解决办法：manifest 直接内置成代码常量，用户装完 App 就有可用清单，
 * 在「模型」页仍可改成自己的地址（覆盖内置值）。
 *
 * ## 为什么提供多档
 * 本地 Whisper 的精度与体积/速度强相关，用户场景差异很大：
 * - 安静环境短句 → tiny 就够，省流量省内存
 * - 一般对话 → base（默认）
 * - 嘈杂/口音重/要更准 → small
 * - 追求最高精度、能接受较慢 → turbo（解码层数少，精度接近 large-v3）
 *
 * 全部来自 k2-fsa 作者官方 HuggingFace 仓库，int8 量化，
 * SHA-256 取自 HF LFS 元数据（与实际字节一致）。
 *
 * ## 云空间要求（实测）
 * - HTTPS（App 的 networkSecurityConfig 全局强制 HTTPS）
 * - 支持 HTTP Range（断点续传）
 * - 单文件直链，不能是下载页
 *
 * 若换成 Kaggle 分发：把 MODEL_BASE 改成
 * `https://www.kaggle.com/api/v1/datasets/download/<owner>/<slug>`，
 * 并给每个 URL 追加 `?raw=true`（否则 Kaggle 返回 compressed/xxx.zip 临时包裹）。
 */
object BuiltInModels {

    private const val HF = "https://huggingface.co"

    /** tokens.txt 各档共用同一份（Whisper 多语种词表），哈希一致 */
    private const val TOKENS_SHA = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
    private const val TOKENS_SIZE = 816730L

    /**
     * 内置 manifest 的 JSON。
     *
     * 用普通 `val` 而不是 `const val`：字符串模板里插值常量不是编译期常量表达式。
     */
    val MANIFEST_JSON: String = """
{
  "version": 2,
  "models": [
    {
      "id": "whisper-tiny-int8",
      "name": "Whisper tiny int8（极速 · 99MB）",
      "kind": "asr-offline",
      "files": [
        {
          "key": "encoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
          "sha256": "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
          "sizeBytes": 12937772
        },
        {
          "key": "decoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
          "sha256": "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
          "sizeBytes": 89855401
        },
        {
          "key": "tokens",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
          "sha256": "$TOKENS_SHA",
          "sizeBytes": $TOKENS_SIZE
        }
      ]
    },
    {
      "id": "whisper-base-int8",
      "name": "Whisper base int8（均衡 · 153MB · 默认）",
      "kind": "asr-offline",
      "files": [
        {
          "key": "encoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx",
          "sha256": "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
          "sizeBytes": 29120534
        },
        {
          "key": "decoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx",
          "sha256": "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
          "sizeBytes": 130672026
        },
        {
          "key": "tokens",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt",
          "sha256": "$TOKENS_SHA",
          "sizeBytes": $TOKENS_SIZE
        }
      ]
    },
    {
      "id": "whisper-small-int8",
      "name": "Whisper small int8（高精度 · 358MB）",
      "kind": "asr-offline",
      "files": [
        {
          "key": "encoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
          "sha256": "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
          "sizeBytes": 112442483
        },
        {
          "key": "decoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
          "sha256": "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
          "sizeBytes": 262226114
        },
        {
          "key": "tokens",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
          "sha256": "$TOKENS_SHA",
          "sizeBytes": $TOKENS_SIZE
        }
      ]
    },
    {
      "id": "whisper-turbo-int8",
      "name": "Whisper turbo int8（最高精度 · 989MB · 较慢）",
      "kind": "asr-offline",
      "files": [
        {
          "key": "encoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-encoder.int8.onnx",
          "sha256": "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b",
          "sizeBytes": 674716297
        },
        {
          "key": "decoder",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-decoder.int8.onnx",
          "sha256": "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547",
          "sizeBytes": 361080764
        },
        {
          "key": "tokens",
          "url": "$HF/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-tokens.txt",
          "sha256": "$TOKENS_SHA",
          "sizeBytes": $TOKENS_SIZE
        }
      ]
    }
  ]
}
"""

    /** 默认使用的模型 id（未显式选择时） */
    const val DEFAULT_MODEL_ID = "whisper-base-int8"

    /** 直接得到解析好的内置清单 */
    fun manifest(): ModelManifest =
        ModelManifestParser.parse(MANIFEST_JSON).getOrElse {
            // 内置常量是编译期写死的，解析失败属于代码缺陷，必须暴露出来
            throw IllegalStateException("内置 manifest 解析失败: ${it.message}", it)
        }
}
