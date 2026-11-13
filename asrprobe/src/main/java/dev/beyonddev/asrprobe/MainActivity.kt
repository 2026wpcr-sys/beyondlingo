package dev.beyonddev.asrprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * ASR Probe —— Whisper tiny 识别质量验证（不是产品）。
 *
 * 目的：回答「Whisper tiny 能不能用于中文/西班牙语双向同传」。
 * 方法：
 *   1. 自带英语样本（已知文本）验证模型加载与基本正确性
 *   2. 用设备 TTS 合成西班牙语、中文 → 重采样到 16k → 识别
 *   3. 输出文本、语言检测、RTF
 *
 * 注意：TTS 合成音比真实人声干净，识别会偏乐观；这是可行性下限测试。
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "ASRProbe"
        const val TARGET_RATE = 16000
    }

    /** 候选模型：目录前缀 + 显示名 */
    private data class ModelSpec(val dir: String, val label: String)

    private val models = listOf(
        ModelSpec("whisper", "tiny int8 (98.8MB)"),
        ModelSpec("whisper/base", "base int8 (153.2MB)"),
    )

    private lateinit var tv: TextView
    private lateinit var resultFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resultFile = File(filesDir, "whisper-result.txt").apply { writeText("") }

        tv = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            text = "Running Whisper test..."
        }
        setContentView(ScrollView(this).apply { addView(tv) })

        Thread {
            try {
                runAll()
            } catch (t: Throwable) {
                log("FATAL ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(800)}")
            }
            runOnUiThread { tv.text = resultFile.readText() }
        }.start()
    }

    private fun log(s: String) {
        Log.i(TAG, s)
        runCatching { resultFile.appendText(s + "\n") }
    }

    private fun runAll() {
        log("=== Whisper 多模型对比 ===")
        log("device: ${android.os.Build.MODEL} / SDK ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        log("")

        for (m in models) {
            log("######## ${m.label}  (${m.dir}) ########")
            runOne(m)
            log("")
        }
        log("=== DONE ===")
    }

    private fun runOne(m: ModelSpec) {
        val enc = "${m.dir}/encoder.int8.onnx"
        val dec = "${m.dir}/decoder.int8.onnx"
        val tok = "${m.dir}/tokens.txt"

        // ---- 加载模型 ----
        val t0 = System.nanoTime()
        val recognizer = try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = TARGET_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    // Whisper 的配置项是 (encoder, decoder, language, task,
                    // tailPaddings, enableTokenTimestamps, enableSegmentTimestamps)
                    // 注意：没有 numThreads/debug，那两个属于 OfflineModelConfig
                    whisper = OfflineWhisperModelConfig(
                        encoder = enc,
                        decoder = dec,
                        language = "",          // 空 = 自动检测
                        task = "transcribe",
                    ),
                    tokens = tok,
                    numThreads = 2,
                    debug = false,
                ),
            )
            OfflineRecognizer(assets, config)
        } catch (t: Throwable) {
            log("LOAD_FAILED ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        log("model loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        log("")

        // ---- 1. 英语自带样本（真人，对照，已知文本） ----
        recognizeAsset(recognizer, "whisper/en-test.wav", "EN real")
        log("")

        // ---- 2. 西语真人样本（Google Translatotron / Fisher-Grounded ST 电话对话） ----
        recognizeAsset(recognizer, "whisper/es-real-1.wav", "ES real 1")
        log("  expect ≈ Y usted no sabrá a menos que usted pida.")
        log("")
        recognizeAsset(recognizer, "whisper/es-real-2.wav", "ES real 2")
        log("  expect ≈ Deja de hacer eso.")
        log("")
        recognizeAsset(recognizer, "whisper/es-real-3.wav", "ES real 3")
        log("  expect ≈ nadar con delfines")
        log("")

        // ---- 3. 西语 / 中文 TTS 合成样本（非真人，仅作对照） ----
        recognizeAsset(recognizer, "whisper/es-tts.wav", "ES tts")
        log("  expect ≈ Buenos dias, como estas? Me alegra mucho verte hoy.")
        log("")
        recognizeAsset(recognizer, "whisper/zh-tts.wav", "ZH tts")
        log("  expect ≈ 今天天气不错，我们下午一起去看电影吧。")
        log("")

        recognizer.release()
    }

    private fun recognizeAsset(rec: OfflineRecognizer, path: String, label: String) {
        val samples = try {
            WavReader.readAsset(assets, path, TARGET_RATE)
        } catch (t: Throwable) {
            log("$label: read failed ${t.message}")
            return
        }
        recognizeSamples(rec, samples, label)
    }

    private fun recognizeSamples(rec: OfflineRecognizer, samples: FloatArray, label: String) {
        val audioSec = samples.size / TARGET_RATE.toDouble()
        val t0 = System.nanoTime()
        val stream = rec.createStream()
        stream.acceptWaveform(samples, TARGET_RATE)
        rec.decode(stream)
        val res = rec.getResult(stream)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        stream.release()

        log("$label out : ${res.text}")
        log("$label lang: ${res.lang}")
        log("$label RTF : ${"%.3f".format(ms / 1000.0 / audioSec)}  (decode ${"%.0f".format(ms)} ms / audio ${"%.2f".format(audioSec)} s)")
    }
}
