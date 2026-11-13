package dev.beyonddev.beyondlingo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.beyonddev.beyondlingo.data.SimultOverlayBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 同传字幕悬浮窗。
 *
 * 用**原生 View**渲染，不用 Compose —— ComposeView 挂到 WindowManager 上
 * 需要 ViewTreeLifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner
 * 三个 owner，在纯 Service 里缺失会抛：
 * `IllegalStateException: ViewTreeLifecycleOwner not found from ComposeView`
 * （已在真机复现）。悬浮窗只有几行文字，原生 View 更简单也更轻。
 *
 * 数据来自 [SimultOverlayBus]（ViewModel 单向推送），本服务不持有识别引擎。
 * 需要 SYSTEM_ALERT_WINDOW 授权。
 */
class SimultOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var bodyView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        ensureOverlay()

        // 订阅字幕总线
        scope.launch {
            SimultOverlayBus.state.collect { snap ->
                statusView?.text = if (snap.running) "同传中" else "已停止"
                bodyView?.text = buildText(snap)
            }
        }
    }

    private fun buildText(snap: SimultOverlayBus.Snapshot): String {
        if (snap.lines.isEmpty() && snap.partial.isEmpty()) return "等待语音…"
        val sb = StringBuilder()
        snap.lines.takeLast(3).forEach { line ->
            sb.append(line.original).append('\n')
            sb.append(line.translated ?: "翻译中…").append('\n')
            sb.append('\n')
        }
        if (snap.partial.isNotEmpty()) sb.append(snap.partial)
        return sb.toString().trim()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun ensureOverlay() {
        if (root != null) return
        if (!canDraw(this)) {
            stopSelf()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(0xE6, 0x10, 0x10, 0x14))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusView = TextView(this).apply {
            text = "已停止"
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        val close = TextView(this).apply {
            text = "  关闭  "
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { stopSelf() }
        }

        header.addView(statusView)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(close)

        bodyView = TextView(this).apply {
            text = "等待语音…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 12
            ellipsize = TextUtils.TruncateAt.END
        }

        container.addView(header)
        container.addView(bodyView)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(120)
        }

        try {
            windowManager.addView(container, p)
            root = container
            params = p
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "addView failed: ${t.message}")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "同传悬浮字幕", NotificationManager.IMPORTANCE_MIN)
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                }
        )
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("同传悬浮字幕")
            .setContentText("在其他应用上显示实时译文")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "simult_overlay"
        private const val NOTIF_ID = 1002
        private const val TAG = "SimultOverlayService"

        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true

        fun start(context: Context) {
            val i = Intent(context, SimultOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SimultOverlayService::class.java))
        }
    }
}
