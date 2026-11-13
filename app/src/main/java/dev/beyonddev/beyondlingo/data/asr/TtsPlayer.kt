package dev.beyonddev.beyondlingo.data.asr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 三路 TTS 共用的播放器：PCM（AudioTrack）+ MP3（MediaPlayer）。
 *
 * 抽出来的原因：TtsEngine / CloudTtsEngine 各自抄了一份 AudioTrack 播放代码，
 * Edge TTS 又要加 MP3 播放——再抄第三份只会让 stop/release 的 bug 复制三遍。
 *
 * 线程模型：playPcm / playMp3 是阻塞调用，由各引擎的单线程 executor 串行调用；
 * [stop] 可在任意线程调用（UI 线程关朗读时），会中断正在播的和排队中的音频。
 * 每次 speak 前引擎应先调 [reset]；play* 开头不调 reset——stop 可能发生在
 * 合成期间，若播之前清掉标志，刚关掉的朗读又会播出来。
 */
class TtsPlayer(private val cacheDir: File? = null) {

    companion object {
        private const val TAG = "TtsPlayer"
        private const val MP3_BITRATE_BPS = 48_000L // Edge 输出 audio-24khz-48kbitrate-mono-mp3
    }

    @Volatile
    private var stopped = false

    private val lock = Any()
    private var currentTrack: AudioTrack? = null
    private var currentPlayer: MediaPlayer? = null
    private var currentLatch: CountDownLatch? = null

    /** 新一段朗读开始前调用：清掉上一段的停止标志（由引擎在 speak 里调） */
    fun reset() {
        stopped = false
    }

    /**
     * 播 PCM16 单声道（阻塞，可被 [stop] 中断）。
     *
     * 注意：这里不调 [reset]——stop 可能发生在合成（generate）期间，
     * 若播之前清掉标志，刚关掉的朗读又会播出来。
     */
    fun playPcm(samples: FloatArray, sampleRate: Int) {
        if (stopped) return
        if (samples.isEmpty()) return
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(pcm.size * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        synchronized(lock) { currentTrack = track }
        try {
            track.write(pcm, 0, pcm.size)
            if (stopped) return
            track.play()
            // 分片等待：原有实现按总时长 sleep（最长 30s），这里切成 50ms 一片
            // 检查 stopped，关朗读时能立刻停而不是播完整段。
            val totalMs = (pcm.size * 1000L / sampleRate).coerceAtMost(30_000L)
            var waited = 0L
            while (waited < totalMs && !stopped) {
                val slice = minOf(50L, totalMs - waited)
                Thread.sleep(slice)
                waited += slice
            }
        } catch (t: Throwable) {
            Log.w(TAG, "playPcm failed: ${t.message}")
        } finally {
            synchronized(lock) {
                if (currentTrack === track) currentTrack = null
            }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /**
     * 播一段完整 MP3（阻塞，直到播完 / 被 [stop] 中断 / 超时）。
     *
     * 必须整段收完再播：turn 可能中途失败，边收边播会播出半截。
     * 超时按 48kbps 码率估算时长 + 5s 余量（10s–120s）。
     * 同 playPcm：开头不调 [reset]，避免清掉合成期间的 stop。
     */
    fun playMp3(bytes: ByteArray) {
        if (stopped) return
        if (bytes.isEmpty()) return
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val tmp = File.createTempFile("edge-tts-", ".mp3", dir)
        try {
            tmp.writeBytes(bytes)
            val latch = CountDownLatch(1)
            val player = MediaPlayer()
            synchronized(lock) {
                currentPlayer = player
                currentLatch = latch
            }
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setOnCompletionListener { latch.countDown() }
                player.setOnErrorListener { _, _, _ ->
                    latch.countDown()
                    true
                }
                FileInputStream(tmp).use { fis ->
                    player.setDataSource(fis.fd)
                    player.prepare()
                }
                if (stopped) return
                player.start()
                val estSec = bytes.size * 8L / MP3_BITRATE_BPS
                val timeoutSec = (estSec + 5).coerceIn(10L, 120L)
                latch.await(timeoutSec, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                Log.w(TAG, "playMp3 failed: ${t.message}")
            } finally {
                synchronized(lock) {
                    if (currentPlayer === player) {
                        currentPlayer = null
                        currentLatch = null
                    }
                }
                runCatching { player.stop() }
                runCatching { player.release() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "playMp3 failed: ${t.message}")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 中断当前播放（正在播的 + 排队中的）；幂等 */
    fun stop() {
        stopped = true
        synchronized(lock) {
            runCatching { currentTrack?.stop() }
            val player = currentPlayer
            if (player != null) {
                runCatching { player.stop() }
                currentLatch?.countDown()
            }
        }
    }

    fun release() = stop()
}
