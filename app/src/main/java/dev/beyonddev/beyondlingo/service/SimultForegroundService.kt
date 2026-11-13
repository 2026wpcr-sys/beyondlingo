package dev.beyonddev.beyondlingo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 同传保活前台服务。
 *
 * RECORD_AUDIO 是 while-in-use 权限：Activity 退到后台后，
 * 单纯在 ViewModel 里持有 AudioRecord 会被系统限制或回收。
 * 声明 foregroundServiceType="microphone" 的前台服务让录音可在后台继续。
 *
 * 录音与识别仍由 SimultViewModel 持有；本服务只负责保活 + 常驻通知。
 */
class SimultForegroundService : Service() {

    private companion object {
        const val CHANNEL_ID = "simult"
        const val NOTIF_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "同声传译",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "麦克风正在采集并本地识别"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("同声传译运行中")
            .setContentText("音频在设备本地识别，仅文字发送到翻译服务")
            .setOngoing(true)
            .build()
    }
}
