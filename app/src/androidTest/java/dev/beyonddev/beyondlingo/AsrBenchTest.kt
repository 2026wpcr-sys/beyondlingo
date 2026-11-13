package dev.beyonddev.beyondlingo

import android.content.Context
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ASR baseline benchmark for sherpa-onnx streaming Zipformer (zh-14M int8).
 *
 * Purpose: answer the one question the research tickets could not —
 * what is the real RTF and memory footprint on this physical device.
 *
 * Prints machine-readable markers to stdout (visible via `adb shell am instrument`
 * or logcat tag "TestRunner"). No assertions on timing; this is a measurement.
 */
@RunWith(AndroidJUnit4::class)
class AsrBenchTest {

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1600 // 100ms @16k
    }

    @Test
    fun benchmarkStreamingAsr() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets: AssetManager = ctx.assets

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "asr/encoder.int8.onnx",
                    decoder = "asr/decoder.int8.onnx",
                    joiner = "asr/joiner.int8.onnx",
                ),
                tokens = "asr/tokens.txt",
                numThreads = 2,
                debug = false,
            ),
            enableEndpoint = true,
        )

        val tLoad0 = System.nanoTime()
        val recognizer = OnlineRecognizer(assets, config)
        val loadMs = (System.nanoTime() - tLoad0) / 1_000_000.0

        val samples = readWav(assets, "asr/test.wav")
        val audioSec = samples.size.toDouble() / SAMPLE_RATE

        val stream = recognizer.createStream()
        var i = 0
        var firstPartialMs = -1.0
        var decodeCalls = 0

        val tDec0 = System.nanoTime()
        while (i < samples.size) {
            val end = minOf(i + CHUNK_SAMPLES, samples.size)
            stream.acceptWaveform(samples.copyOfRange(i, end), SAMPLE_RATE)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decodeCalls++
            }
            if (firstPartialMs < 0.0) {
                val partial = recognizer.getResult(stream).text
                if (partial.isNotEmpty()) {
                    firstPartialMs = (System.nanoTime() - tDec0) / 1_000_000.0
                }
            }
            i = end
        }
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
            decodeCalls++
        }
        val decodeMs = (System.nanoTime() - tDec0) / 1_000_000.0
        val text = recognizer.getResult(stream).text

        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)

        println("ASR_LOAD_MS=$loadMs")
        println("ASR_AUDIO_SEC=$audioSec")
        println("ASR_DECODE_MS=$decodeMs")
        println("ASR_RTF=${decodeMs / 1000.0 / audioSec}")
        println("ASR_FIRST_PARTIAL_MS=$firstPartialMs")
        println("ASR_DECODE_CALLS=$decodeCalls")
        println("ASR_HEAP_MB=$heapMb")
        println("ASR_TEXT=$text")

        stream.release()
        recognizer.release()
    }

    /** Minimal RIFF/WAVE parser: walks chunks, finds "data", converts PCM16 to float. */
    private fun readWav(assets: AssetManager, name: String): FloatArray {
        val bytes = assets.open(name).use { it.readBytes() }
        require(bytes.size > 44) { "wav too small" }

        var pos = 12 // skip RIFF....WAVE
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
        require(dataStart > 0) { "no data chunk in $name" }

        val n = dataLen / 2
        val out = FloatArray(n)
        for (k in 0 until n) {
            val lo = bytes[dataStart + k * 2].toInt() and 0xFF
            val hi = bytes[dataStart + k * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            out[k] = s / 32768.0f
        }
        return out
    }
}
