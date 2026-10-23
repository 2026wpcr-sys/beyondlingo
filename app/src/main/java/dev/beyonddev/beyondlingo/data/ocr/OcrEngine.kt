package dev.beyonddev.beyondlingo.data.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR 文字识别。
 *
 * 使用 ML Kit 的拉丁文字识别模型：
 * - **完全离线**，不联网、不上传图片
 * - 模型随 APK 打包，安装即用（不依赖 Google Play 服务）
 * - 支持拉丁字母、数字、常见符号
 *
 * 局限：
 * - 不含中日韩模型（中文识别需 `text-recognition-chinese`，会额外增大 ~20MB）
 * - 对艺术字、手写体、低分辨率图片识别率有限
 * - 竖排文字、表格结构还原能力弱
 */
class OcrEngine(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 识别图片中的文字。
     *
     * @param uri 图片 Uri
     * @return 识别结果；无文字时返回空 [OcrResult]
     */
    suspend fun recognize(uri: Uri): OcrResult {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "无法读取图片", e)
            throw OcrException("无法读取图片，请换一张试试", e)
        }
        return recognizeImage(image)
    }

    /**
     * 识别 Bitmap 中的文字（拍照路径使用）。
     */
    suspend fun recognizeBitmap(bitmap: android.graphics.Bitmap): OcrResult {
        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {
            Log.e(TAG, "无法处理位图", e)
            throw OcrException("无法处理这张照片", e)
        }
        return recognizeImage(image)
    }

    /** 对 InputImage 执行识别 */
    private suspend fun recognizeImage(image: InputImage): OcrResult {
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(visionText.text.orEmpty())
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) {
                        Log.e(TAG, "OCR 失败", e)
                        cont.resumeWithException(
                            OcrException(e.message ?: "文字识别失败", e)
                        )
                    }
                }
                .addOnCanceledListener {
                    if (cont.isActive) {
                        cont.resumeWithException(OcrException("识别被取消"))
                    }
                }
        }

        val trimmed = text.trim()
        return OcrResult(
            text = trimmed,
            blockCount = trimmed.lines().count { it.isNotBlank() },
            lineCount = trimmed.lines().count { it.isNotBlank() },
        )
    }

    /** 释放识别器资源 */
    fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
            // 忽略
        }
    }

    private companion object {
        const val TAG = "BeyondLingoOcr"
    }
}

/** OCR 识别结果 */
data class OcrResult(
    /** 识别出的完整文本 */
    val text: String,
    /** 文本块数量（预留，当前实现未填充） */
    val blockCount: Int,
    /** 有效行数 */
    val lineCount: Int,
) {
    val isEmpty: Boolean get() = text.isBlank()

    /** 字符数，用于 UI 提示 */
    val charCount: Int get() = text.length
}

/** OCR 相关异常 */
class OcrException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
