package dev.beyonddev.beyondlingo.data.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * 16kHz / 单声道 / PCM16 麦克风采集。
 *
 * 设计要点：
 * - 采集线程只做「搬运」，不做任何推理，避免阻塞 read 导致丢帧
 * - 每 100ms 回调一次，供上层喂给 sherpa-onnx
 */
class AudioRecorder(
    private val sampleRate: Int = 16_000,
    private val chunkMs: Int = 100,
    private val onSamples: (FloatArray) -> Unit,
    private val onError: (String) -> Unit = {},
) {

    private companion object {
        const val TAG = "AudioRecorder"
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError("设备不支持 16kHz 录音（getMinBufferSize=$minBuf）")
            return
        }
        val bufferBytes = maxOf(minBuf * 2, sampleRate / 2)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            onError("创建 AudioRecord 失败：${t.message}")
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("AudioRecord 初始化失败（可能缺少录音权限）")
            return
        }

        record = rec
        running = true
        rec.startRecording()

        val chunkSamples = sampleRate / 1000 * chunkMs
        thread = Thread({
            val shortBuf = ShortArray(chunkSamples)
            while (running) {
                val n = try {
                    rec.read(shortBuf, 0, shortBuf.size)
                } catch (t: Throwable) {
                    Log.w(TAG, "read failed", t)
                    -1
                }
                if (n > 0) {
                    val f = FloatArray(n)
                    for (i in 0 until n) f[i] = shortBuf[i] / 32768f
                    onSamples(f)
                } else if (n < 0) {
                    onError("录音读取失败（code=$n）")
                    break
                }
            }
        }, "asr-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Throwable) {
            }
            it.release()
        }
        record = null
    }
}
