package dev.beyonddev.beyondlingo.vm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beyonddev.beyondlingo.data.ocr.OcrEngine
import dev.beyonddev.beyondlingo.data.ocr.OcrResult
import dev.beyonddev.beyondlingo.service.ClipboardWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** OCR 页 UI 状态 */
data class OcrUiState(
    /** 用户从相册选的图片 */
    val imageUri: Uri? = null,
    /** 拍照得到的位图 */
    val bitmap: Bitmap? = null,
    val isRecognizing: Boolean = false,
    val result: OcrResult? = null,
    val error: String? = null,
)

class OcrViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = OcrEngine(app)

    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    /** 用户从相册选择图片 */
    fun onImagePicked(uri: Uri) {
        _uiState.value = OcrUiState(imageUri = uri, isRecognizing = true)
        recognize { engine.recognize(uri) }
    }

    /** 用户拍照 */
    fun onPhotoTaken(bitmap: Bitmap) {
        _uiState.value = OcrUiState(bitmap = bitmap, isRecognizing = true)
        recognize { engine.recognizeBitmap(bitmap) }
    }

    /** 统一的识别流程与错误处理 */
    private fun recognize(block: suspend () -> OcrResult) {
        viewModelScope.launch {
            try {
                val result = block()
                _uiState.value = _uiState.value.copy(
                    isRecognizing = false,
                    result = result,
                    error = if (result.isEmpty) "没有识别到文字，换一张更清晰的图片试试" else null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isRecognizing = false,
                    result = null,
                    error = e.message ?: "识别失败，请重试",
                )
            }
        }
    }

    /** 复制识别结果 */
    fun copyResult(context: Context) {
        val text = _uiState.value.result?.text.orEmpty()
        if (text.isNotBlank()) {
            ClipboardWriter.copy(context, text, label = "ocr")
        }
    }

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }
}
