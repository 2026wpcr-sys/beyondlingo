package dev.beyonddev.beyondlingo.data.asr

/**
 * Microsoft Edge 在线 TTS 的 Sec-MS-GEC v2 令牌。
 *
 * 协议来源：rany2/edge-tts **7.3.1**（2026-11-13 当天 master）
 * `src/edge_tts/drm.py` + `src/edge_tts/constants.py` 的逐行移植。
 *
 * ⚠️ 不要照抄旧教程 / 不要抄 HearWrite 的 io/edge/EdgeTts.kt：
 * 微软 2026-10 改了校验，v1（标准 SHA-256 + `Sec-MS-GEC-Version: 1-…`）
 * 现在直接 403。v2 的 token 是「魔改 SHA-256 + CS64」拼出来的
 * `<16 位 hex>-<64 位 hex>`，版本号常量是 `2-143.0.3650.75`。
 *
 * 已用真机服务验证（2026-11-13）：同一算法生成的 token 拿到
 * `101 Switching Protocols` 并收到 MP3 音频。
 *
 * 本文件是纯 Kotlin（无 Android 依赖），可在 JVM 单测里直接跑。
 * 校验向量见 `EdgeTtsTest`（由 Python 版 drm.py 生成）。
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object EdgeTtsToken {

    /** Edge Read Aloud 的固定 client token（公开常量，见 edge-tts constants.py） */
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /**
     * Chromium 版本号 —— 微软滚动更新；若某天开始 403，
     * 先对照 rany2/edge-tts 的最新 release 同步这个值
     * （连同 UA 一起改），而不是调自己的代码。
     */
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val CHROMIUM_MAJOR_VERSION = "143"

    /** Sec-MS-GEC-Version 查询参数（v2 校验的版本号，集中管理，勿散落） */
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

    private const val WIN_EPOCH = 11_644_473_600L // 1601-01-01 至 1970-01-01 的秒数

    @Volatile
    private var clockSkewSeconds = 0.0

    /** 403 且响应带 Date 头时调用：按服务器时间纠正一次本地时钟偏差 */
    fun adjustClockSkew(deltaSeconds: Double) {
        clockSkewSeconds += deltaSeconds
    }

    private fun nowUnixSeconds(): Double =
        System.currentTimeMillis() / 1000.0 + clockSkewSeconds

    /**
     * 生成当前 5 分钟窗口的 Sec-MS-GEC v2 token。
     *
     * @param unixSeconds 显式时间戳（仅单测用；默认取当前时间 + 时钟偏差）
     */
    fun generate(unixSeconds: Double = nowUnixSeconds()): String {
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300.0
        val ticks100ns = Math.round(ticks * 1e7)
        val input = "$ticks100ns$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val (cs64, sha256) = tokenHash(input)
        return "$cs64-$sha256"
    }

    internal fun tokenHash(data: ByteArray): Pair<String, String> {
        val digest = sha256Digest(data)
        val cs64 = cs64Digest(data, digest)
        return bytesToHexUpper(packLe32(cs64.first, cs64.second)) to
            bytesToHexUpper(digest)
    }

    // ---- 以下为 drm.py 的逐行移植（UInt 运算天然按 2^32 回绕，与 Python 的 & 0xFFFFFFFF 等价） ----

    private val sha256Initial = uintArrayOf(
        0x6A09E667u, 0xBB67AE85u, 0x3C6EF372u, 0xA54FF53Au,
        0x510E527Fu, 0x9B05688Cu, 0x1F83D9ABu, 0x5BE0CD19u,
    )

    private val sha256State = uintArrayOf(
        0xFD9FBEA2u, 0x91243CE7u, 0xBE499094u, 0xCD2E1E0Au,
        0x6C006F49u, 0x04BD35ABu, 0xFEB0A7E4u, 0x03EFFBD2u,
    )

    private val sha256Schedule = uintArrayOf(
        0xEB24BF9Eu, 0x1558691Fu, 0xA0C09EE2u, 0x0CFA32BAu,
        0xA622EE8Cu, 0xD1DD9491u, 0xC0DD25A0u, 0xF9E70AB6u,
        0xF0C31573u, 0x7058CE4Eu, 0x7188E606u, 0x00E5AB33u,
        0xD258B060u, 0x72766564u,
    )

    private val sha256RoundConstants = uintArrayOf(
        0x428A2F98u, 0x71374491u, 0xB5C0FBCFu, 0xE9B5DBA5u,
        0x3956C25Bu, 0x59F111F1u, 0x923F82A4u, 0xAB1C5ED5u,
        0xD807AA98u, 0x12835B01u, 0x243185BEu, 0x550C7DC3u,
        0x72BE5D74u, 0x80DEB1FEu, 0x9BDC06A7u, 0xC19BF174u,
        0xE49B69C1u, 0xEFBE4786u, 0x0FC19DC6u, 0x240CA1CCu,
        0x2DE92C6Fu, 0x4A7484AAu, 0x5CB0A9DCu, 0x76F988DAu,
        0x983E5152u, 0xA831C66Du, 0xB00327C8u, 0xBF597FC7u,
        0xC6E00BF3u, 0xD5A79147u, 0x06CA6351u, 0x14292967u,
        0x27B70A85u, 0x2E1B2138u, 0x4D2C6DFCu, 0x53380D13u,
        0x650A7354u, 0x766A0ABBu, 0x81C2C92Eu, 0x92722C85u,
        0xA2BFE8A1u, 0xA81A664Bu, 0xC24B8B70u, 0xC76C51A3u,
        0xD192E819u, 0xD6990624u, 0xF40E3585u, 0x106AA070u,
        0x19A4C116u, 0x1E376C08u, 0x2748774Cu, 0x34B0BCB5u,
        0x391C0CB3u, 0x4ED8AA4Au, 0x5B9CCA4Fu, 0x682E6FF3u,
        0x748F82EEu, 0x78A5636Fu, 0x84C87814u, 0x8CC70208u,
        0x90BEFFFAu, 0xA4506CEBu, 0xBEF9A3F7u, 0xC67178F2u,
    )

    /** v2 对标准 SHA-256 的改动点之一：第 15 个 round 常量被替换 */
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7u
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80u
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0u

    private val cs64Coefficients = arrayOf(
        0xE82E91B1u to 0xB32700D0u, 0x44DE24C6u to 0x88DFF51Du,
        0x3BEB65BCu to 0x29514038u, 0xC89C5933u to 0xB328CBDFu,
        0x8D1EC211u to 0xD405B4D5u, 0x314EF5D5u to 0x26E55F3Cu,
        0xE92491B1u to 0x607801C6u, 0xF0A5B9D5u to 0x82B2C1FBu,
        0x06064CF3u to 0xFA51A3DAu, 0xDA79FA60u to 0x207CC464u,
        0xBED20560u to 0x3C3C74F3u, 0x89F9C9F8u to 0xD2D14B61u,
        0x35FA38C6u to 0xBB04A2C1u, 0xCB49E3C6u to 0xC5626167u,
    )
    private const val CS64_FACTOR = 0x82893933u
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1Au
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000u

    private fun rotr32(x: UInt, n: Int): UInt = (x shr n) or (x shl (32 - n))
    private fun rol16(x: UInt): UInt = (x shl 16) or (x shr 16)

    private fun sigma0(x: UInt): UInt =
        (x shr 7) xor (x shl 25) xor (x shr 18) xor (x shl 14) xor (x shr 3)

    private fun sigma1(x: UInt): UInt =
        (x shr 17) xor (x shl 15) xor (x shr 19) xor (x shl 13) xor (x shr 10)

    private fun sum0(x: UInt): UInt = rotr32(x, 2) xor rotr32(x, 13) xor rotr32(x, 22)
    private fun sum1(x: UInt): UInt = rotr32(x, 6) xor rotr32(x, 11) xor rotr32(x, 25)

    private fun ch(e: UInt, f: UInt, g: UInt): UInt = (e and f) xor (e.inv() and g)
    private fun maj(a: UInt, b: UInt, c: UInt): UInt = (a and b) xor (a and c) xor (b and c)

    /** 标准 SHA-256 轮函数；v2 只改了初始 state / 展开表 / K[14]，轮函数本身不变 */
    private fun sha256Rounds(
        state: UIntArray,
        words: UIntArray,
        firstRound: Int,
    ): UIntArray {
        val constants = if (firstRound == 14) {
            // K[14]（0x9BDC06A7）被 _SHA256_ADJUSTMENT（0xC9DC06A7）替换
            UIntArray(64) { i ->
                if (i == 14) SHA256_ADJUSTMENT else sha256RoundConstants[i]
            }
        } else {
            sha256RoundConstants
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in firstRound until 64) {
            val t1 = h + sum1(e) + ch(e, f, g) + constants[i] + words[i]
            val t2 = sum0(a) + maj(a, b, c)
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return uintArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: UIntArray, firstWord: Int) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val s1 = sigma1(x)
            val s0 = sigma0(y)
            words[index] = s1 + words[index - 7] + s0 + words[index - 16]
        }
    }

    /** drm.py _sha256_init：只用 token 前 7 个字节算初始 state */
    private fun sha256Init(data: ByteArray): UIntArray {
        val words = UIntArray(64)
        words[14] = ((data[0].toInt() and 0xFF) shl 16 or
            ((data[1].toInt() and 0xFF) shl 8) or
            (data[2].toInt() and 0xFF)).toUInt()
        words[15] = be32(data, 3)
        for (index in 16..29) {
            var value = sha256Schedule[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    sigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    sigma0(words[index - 15])
                }
            }
            words[index] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            SHA256_ADJUSTMENT - sha256RoundConstants[14]
        expandSchedule(words, 31)
        val working = sha256Rounds(sha256State, words, 14)
        return UIntArray(8) { i -> sha256Initial[i] + working[i] }
    }

    /** drm.py _sha256_digest：魔改版 SHA-256（数据从第 7 字节起参与，长度域按 57+len 计） */
    internal fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val tail = data.copyOfRange(7, data.size)
        val padLen = (64 - (tail.size + 1 + 8) % 64) % 64
        val padded = ByteArray(tail.size + 1 + padLen + 8)
        tail.copyInto(padded)
        padded[tail.size] = 0x80.toByte()
        val bitLen = length.toLong() * 8L
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = ((bitLen ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = UIntArray(64)
            for (i in 0 until 16) words[i] = be32(padded, offset + i * 4)
            expandSchedule(words, 16)
            val working = sha256Rounds(state, words, 0)
            state = UIntArray(8) { i -> state[i] + working[i] }
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val w = state[i]
            out[i * 4] = (w shr 24).toByte()
            out[i * 4 + 1] = (w shr 16).toByte()
            out[i * 4 + 2] = (w shr 8).toByte()
            out[i * 4 + 3] = w.toByte()
        }
        return out
    }

    /** drm.py _cs64_digest */
    internal fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<UInt, UInt> {
        val keyA = le32(digest, 0) or 1u
        val keyB = le32(digest, 4) or 1u
        val factorA = keyA * CS64_FACTOR
        val factorB = keyB * CS64_FACTOR
        val terms = ArrayList<UInt>(32)
        val offsets = ArrayList<UInt>(32)
        for (i in cs64Coefficients.indices) {
            val (coeff, off) = cs64Coefficients[i]
            terms.add(coeff * if (i % 2 == 0) factorA else factorB)
            offsets.add(off)
        }
        for ((index, word) in wordsLe4Even(byteArrayOf(0) + data).withIndex()) {
            var term = word * if (index % 2 == 0) keyA else keyB
            var offset = rol16(word) * if (index % 2 == 0) 0xEF0569FBu else 0xC31713DBu
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factorA
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(offset)
        }
        var swapAcc = 0u
        var swapLast = 0u
        var state = 0u
        var acc = 0u
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16(offsets[index] + x)
            var q = t * 0x76F50F34u
            swapAcc += rol16(q) * 0xC3EFEA97u + q
            t = rol16(offsets[index + 1] + y)
            q = t * 0x1881CE3Cu
            swapLast = rol16(q) * 0x35BD1EC9u + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111u
            state = rol16(state) * 0x87085B9Fu
            state = rol16(state) * 0x12CEB96Du
            state = rol16(state) * 0x257E1D83u
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5u
            t = rol16(t) * 0xD38396FFu
            state = rol16(rol16(t) * 0x7C932B89u) * 0xBFA49F69u
            acc += state
            index += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    private fun wordsLe4Even(data: ByteArray): UIntArray {
        val pad = (4 - data.size % 4) % 4
        val padded = ByteArray(data.size + pad)
        data.copyInto(padded)
        val count = padded.size / 4
        val words = ArrayList<UInt>(count + 1)
        for (i in 0 until count) words.add(le32(padded, i * 4))
        if (words.size % 2 != 0) words.add(0u)
        return words.toUIntArray()
    }

    private fun be32(bytes: ByteArray, offset: Int): UInt =
        ((bytes[offset].toInt() and 0xFF) shl 24 or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)).toUInt()

    private fun le32(bytes: ByteArray, offset: Int): UInt =
        ((bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)).toUInt()

    private fun packLe32(a: UInt, b: UInt): ByteArray {
        val out = ByteArray(8)
        out[0] = a.toByte(); out[1] = (a shr 8).toByte()
        out[2] = (a shr 16).toByte(); out[3] = (a shr 24).toByte()
        out[4] = b.toByte(); out[5] = (b shr 8).toByte()
        out[6] = (b shr 16).toByte(); out[7] = (b shr 24).toByte()
        return out
    }

    private fun bytesToHexUpper(bytes: ByteArray): String {
        val hex = "0123456789ABCDEF"
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(hex[v shr 4])
                append(hex[v and 0x0F])
            }
        }
    }
}
