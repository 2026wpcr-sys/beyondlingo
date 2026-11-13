package dev.beyonddev.asrprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * ASR Probe —— sherpa-onnx 能力验证工具（不是产品）。
 *
 * 本次要回答的问题：模型能否从 APK 外的绝对路径加载？
 * 决定「模型放云空间、首启下载」方案是否可行。
 *
 * 方法：先测 assets（对照组，已知可行），再测绝对路径。
 * 日志同时写 filesDir/result.txt —— native 崩溃时 logcat 可能丢失，文件更可靠。
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "ASRProbe"
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1600
        val MODEL_FILES = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"
        )
    }

    private lateinit var tv: TextView
    private lateinit var resultFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resultFile = File(filesDir, "result.txt")
        resultFile.writeText("")

        tv = TextView(this).apply {
            setPadding(56, 56, 56, 56)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            text = "Running..."
        }
        setContentView(ScrollView(this).apply { addView(tv) })

        Thread {
            try {
                runTest()
            } catch (t: Throwable) {
                logBoth("TOP_LEVEL_EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
                runOnUiThread { tv.text = resultFile.readText() }
            }
        }.start()
    }

    /** logcat + 文件双写，确保崩溃前的记录不丢。 */
    private fun logBoth(msg: String) {
        Log.i(TAG, msg)
        try {
            resultFile.appendText(msg + "\n")
        } catch (_: Throwable) {
        }
    }

    private fun runTest() {
        logBoth("=== ASR Probe / external path test ===")
        logBoth("device: ${android.os.Build.MODEL} / SDK ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.SUPPORTED_ABIS.joinToString()}")

        // ---- 准备：把模型复制到 filesDir（模拟"云空间下载后落盘"）----
        val modelDir = File(filesDir, "asr").apply { mkdirs() }
        for (n in MODEL_FILES) {
            val dst = File(modelDir, n)
            if (!dst.exists() || dst.length() == 0L) {
                assets.open("asr/$n").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        logBoth("modelDir=${modelDir.absolutePath} files=${modelDir.list()?.size}")
        logBoth("")

        // ---- 对照组 A：assets 相对路径 ----
        logBoth("[A] ASSETS path")
        val assetsOk = try {
            val cfg = buildConfig(
                enc = "asr/encoder.int8.onnx",
                dec = "asr/decoder.int8.onnx",
                joi = "asr/joiner.int8.onnx",
                tok = "asr/tokens.txt",
            )
            val t0 = System.nanoTime()
            val r = OnlineRecognizer(assets, cfg)
            logBoth("  A RESULT = SUCCESS (load ${(System.nanoTime() - t0) / 1_000_000} ms)")
            r.release()
            true
        } catch (t: Throwable) {
            logBoth("  A RESULT = FAILED: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        logBoth("")

        // ---- 实验组 B（已确认会 native 崩溃，跳过以免杀死进程）----
        logBoth("[B] ABSOLUTE path via public ctor — SKIPPED (known native crash)")

        // ---- 实验组 C：反射调用 private newFromFile ----
        logBoth("")
        logBoth("[C] ABSOLUTE path via reflected newFromFile")
        try {
            val cfg = buildConfig(
                enc = File(modelDir, "encoder.int8.onnx").absolutePath,
                dec = File(modelDir, "decoder.int8.onnx").absolutePath,
                joi = File(modelDir, "joiner.int8.onnx").absolutePath,
                tok = File(modelDir, "tokens.txt").absolutePath,
            )
            val t0 = System.nanoTime()
            val r = createFromFile(cfg)
            logBoth("  C RESULT = SUCCESS (load ${(System.nanoTime() - t0) / 1_000_000} ms)")

            val samples = readWav("asr/test.wav")
            val audioSec = samples.size.toDouble() / SAMPLE_RATE
            val stream = r.createStream()
            var i = 0
            val td = System.nanoTime()
            while (i < samples.size) {
                val end = minOf(i + CHUNK_SAMPLES, samples.size)
                stream.acceptWaveform(samples.copyOfRange(i, end), SAMPLE_RATE)
                while (r.isReady(stream)) r.decode(stream)
                i = end
            }
            stream.inputFinished()
            while (r.isReady(stream)) r.decode(stream)
            val ms = (System.nanoTime() - td) / 1_000_000.0
            logBoth("  C text = ${r.getResult(stream).text}")
            logBoth("  C RTF  = ${String.format("%.3f", ms / 1000.0 / audioSec)}")
            stream.release()
            r.release()
        } catch (t: Throwable) {
            logBoth("  C RESULT = FAILED: ${t.javaClass.simpleName}: ${t.message}")
            logBoth("  ${t.stackTraceToString().take(600)}")
        }

        logBoth("")
        logBoth("=== DONE ===")
        runOnUiThread { tv.text = resultFile.readText() }
    }

    /**
     * 绕过 Kotlin 包装：native 层有 newFromFile（支持绝对路径），但被声明为 private。
     * 用 Unsafe.allocateInstance 跳过构造器，反射调 newFromFile，再把 ptr/config 填回去。
     */
    private fun createFromFile(config: OnlineRecognizerConfig): OnlineRecognizer {
        val cls = OnlineRecognizer::class.java

        val unsafeCls = Class.forName("sun.misc.Unsafe")
        val f = unsafeCls.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = f.get(null)
        val alloc = unsafeCls.getMethod("allocateInstance", Class::class.java)
        val inst = alloc.invoke(unsafe, cls) as OnlineRecognizer
        logBoth("  allocated instance without ctor")

        val newFromFile = cls.getDeclaredMethod("newFromFile", OnlineRecognizerConfig::class.java)
        newFromFile.isAccessible = true
        val ptr = newFromFile.invoke(inst, config) as Long
        logBoth("  newFromFile ptr=$ptr")
        require(ptr != 0L) { "newFromFile returned null ptr" }

        cls.getDeclaredField("ptr").apply { isAccessible = true }.setLong(inst, ptr)
        cls.getDeclaredField("config").apply { isAccessible = true }.set(inst, config)
        logBoth("  ptr/config injected")
        return inst
    }

    private fun buildConfig(enc: String, dec: String, joi: String, tok: String) =
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = enc, decoder = dec, joiner = joi),
                tokens = tok,
                numThreads = 2,
                debug = false,
            ),
            enableEndpoint = true,
        )

    private fun readWav(name: String): FloatArray {
        val bytes = assets.open(name).use { it.readBytes() }
        var pos = 12
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                dataStart = pos + 8
                dataLen = minOf(size, bytes.size - dataStart)
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataStart > 0) { "no data chunk" }
        val n = dataLen / 2
        val out = FloatArray(n)
        for (k in 0 until n) {
            val lo = bytes[dataStart + k * 2].toInt() and 0xFF
            val hi = bytes[dataStart + k * 2 + 1].toInt()
            out[k] = ((hi shl 8) or lo) / 32768.0f
        }
        return out
    }
}
