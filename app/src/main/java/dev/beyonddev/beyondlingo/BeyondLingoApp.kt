package dev.beyonddev.beyondlingo

import android.app.Application
import dev.beyonddev.beyondlingo.data.SettingsStore
import dev.beyonddev.beyondlingo.service.FloatingWindowService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 无界译 AI / BeyondLingo AI
 *
 * 开发者：无界者（BeyondDev）
 * 口号：跨越语言边界，让沟通自由发生。
 *
 * 注意：这里刻意**不**在主线程初始化 Room 或网络客户端。
 * 数据库首次访问会触发磁盘 IO + 建表，放在 Application.onCreate 会导致
 * 冷启动卡顿甚至 ANR；改为懒加载，由首次使用方在 IO 线程触发。
 */
class BeyondLingoApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        restoreBubble()
    }

    /** 进程重启后，若用户开启了悬浮球则自动恢复 */
    private fun restoreBubble() {
        appScope.launch {
            try {
                val settings = dev.beyonddev.beyondlingo.data.SettingsStore(this@BeyondLingoApp)
                    .settings.first()
                if (settings.bubbleEnabled) {
                    dev.beyonddev.beyondlingo.service.FloatingWindowService
                        .setBubbleVisible(this@BeyondLingoApp, true)
                }
            } catch (_: Exception) {
                // 静默失败
            }
        }
    }

    companion object {
        lateinit var instance: BeyondLingoApp
            private set
    }
}

/**
 * 品牌信息常量。
 *
 * 集中定义，方便关于页 / 启动页 / 分享文案统一引用。
 */
object Brand {
    /** 中文应用名 */
    const val APP_NAME_ZH = "无界译 AI"

    /** 英文应用名 */
    const val APP_NAME_EN = "BeyondLingo AI"

    /** 版本号 */
    const val VERSION = "1.5.2"

    /** 中文署名 */
    const val AUTHOR_ZH = "无界者"

    /** 英文署名 */
    const val AUTHOR_EN = "BeyondDev"

    /** 完整署名 */
    const val AUTHOR_FULL = "无界者（BeyondDev）"

    /** 中文口号 */
    const val SLOGAN_ZH = "跨越语言边界，让沟通自由发生。"

    /** 英文口号 */
    const val SLOGAN_EN = "Break Language Barriers. Speak Freely."

    /** 上游项目 */
    const val UPSTREAM_NAME = "STranslate"
    const val UPSTREAM_AUTHOR = "zggsong"
    const val UPSTREAM_URL = "https://github.com/STranslate/STranslate"

    /** 本项目声明 */
    const val DERIVATION_NOTE =
        "本项目基于 STranslate（MIT，作者 zggsong）二次开发。" +
            "原项目为 WPF 桌面实现，Android 版本由 BeyondDev 独立重写。"
}
