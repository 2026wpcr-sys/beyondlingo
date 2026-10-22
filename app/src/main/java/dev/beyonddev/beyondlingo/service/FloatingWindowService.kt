package dev.beyonddev.beyondlingo.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.beyonddev.beyondlingo.MainActivity
import dev.beyonddev.beyondlingo.R
import dev.beyonddev.beyondlingo.data.SettingsStore
import dev.beyonddev.beyondlingo.data.TranslateRepository
import dev.beyonddev.beyondlingo.data.engine.TranslateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮窗服务：展示划词/剪贴板触发后的译文。
 *
 * 用 Compose 渲染内容，通过 [WindowManager] 挂载为
 * `TYPE_APPLICATION_OVERLAY` 悬浮窗。
 *
 * 同时以前台服务运行，用于：
 * 1. 满足 Android 8+ 后台服务要求
 * 2. 让剪贴板监听在后台存活（Android 10+ 限制）
 *
 * 继承 [LifecycleService] 即可获得 Lifecycle / ViewModelStore / SavedStateRegistry
 * 三个 owner，ComposeView 需要它们才能正常工作。
 */
class FloatingWindowService : LifecycleService(),
    ViewModelStoreOwner, SavedStateRegistryOwner {

    /** ComposeView 需要 ViewModelStoreOwner */
    private val vmsStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmsStore

    /** ComposeView 需要 SavedStateRegistryOwner */
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 剪贴板监听器，由 ACTION_START_CLIPBOARD 控制启停 */
    private var clipboardWatcher: ClipboardWatcher? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoDismissRunnable: Runnable? = null

    // 当前展示状态（Compose 可观察）
    private var sourceText by mutableStateOf("")
    private var resultText by mutableStateOf("")
    private var isTranslating by mutableStateOf(false)
    private var errorText by mutableStateOf<String?>(null)

    override fun onCreate() {
        super.onCreate()
        // ComposeView 依赖 SavedStateRegistry，需在 setContent 前初始化
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_TRANSLATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) showAndTranslate(text)
            }
            ACTION_START_CLIPBOARD -> {
                startClipboardWatch()
            }
            ACTION_STOP_CLIPBOARD -> {
                stopClipboardWatch()
                // 若悬浮窗也未显示，则停止服务
                if (composeView == null) stopSelf()
            }
            ACTION_DISMISS -> {
                hideOverlay()
                if (clipboardWatcher == null) stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startClipboardWatch() {
        if (clipboardWatcher != null) return
        clipboardWatcher = ClipboardWatcher(applicationContext).also { it.start() }
        updateNotification("剪贴板监听中", "复制任意文字即可翻译")
    }

    private fun stopClipboardWatch() {
        clipboardWatcher?.stop()
        clipboardWatcher = null
    }

    // ---------- 悬浮窗 ----------

    @SuppressLint("InflateParams")
    private fun ensureOverlay() {
        if (composeView != null) return

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent { OverlayContent() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 260
        }

        composeView = view
        layoutParams = params

        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            composeView = null
            layoutParams = null
        }
    }

    private fun hideOverlay() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null

        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // 已被移除
            }
        }
        composeView = null
        layoutParams = null
    }

    private fun moveOverlay(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val view = composeView ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // 忽略
        }
    }

    // ---------- 翻译 ----------

    private fun showAndTranslate(text: String) {
        // 先读设置，决定是否静默
        scope.launch {
            val settings = SettingsStore(applicationContext).settings.first()

            // C. 静默模式：只翻译，不弹窗，结果写通知
            if (settings.silentAutoTranslate) {
                val repo = TranslateRepository(applicationContext)
                val outcome = repo.translateWithCurrentSettings(text, checkCacheFirst = true)
                when (outcome) {
                    is TranslateOutcome.Success ->
                        updateNotification(text.take(40), outcome.text.take(200))
                    is TranslateOutcome.Failure ->
                        updateNotification("翻译失败", outcome.message.take(200))
                }
                return@launch
            }

            // 正常模式：弹悬浮窗
            ensureOverlay()

            sourceText = text
            resultText = ""
            errorText = null
            isTranslating = true

            scheduleAutoDismiss(settings.floatingAutoDismiss, settings.floatingAutoDismissMs)

            val repo = TranslateRepository(applicationContext)
            val outcome = repo.translateWithCurrentSettings(text, checkCacheFirst = true)

            when (outcome) {
                is TranslateOutcome.Success -> {
                    resultText = outcome.text
                    errorText = null
                    if (composeView == null) {
                        updateNotification(text.take(40), outcome.text.take(200))
                    }
                }
                is TranslateOutcome.Failure -> {
                    resultText = ""
                    errorText = outcome.message
                    if (composeView == null) {
                        updateNotification("翻译失败", outcome.message.take(200))
                    }
                }
            }
            isTranslating = false

            scheduleAutoDismiss(settings.floatingAutoDismiss, settings.floatingAutoDismissMs)
        }
    }

    private fun scheduleAutoDismiss(enabled: Boolean, delayMs: Long) {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null

        if (!enabled) return

        val runnable = Runnable {
            hideOverlay()
            if (clipboardWatcher == null) stopSelf()
        }
        autoDismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun copyResult() {
        if (resultText.isBlank()) return
        SelfWriteMarker.copyToClipboard(applicationContext, resultText)
    }

    // ---------- Compose 内容 ----------

    @Composable
    private fun OverlayContent() {
        MaterialTheme {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            moveOverlay(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "翻译",
                        color = Color(0xFF8AB4F8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Row {
                        IconButton(
                            onClick = { copyResult() },
                            modifier = Modifier.size(28.dp),
                            enabled = resultText.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制",
                                tint = if (resultText.isNotBlank()) Color(0xFFCCCCCC)
                                else Color(0xFF555555),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                hideOverlay()
                                stopSelf()
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = Color(0xFFCCCCCC),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Text(
                    text = sourceText,
                    color = Color(0xFF9AA0A6),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    when {
                        isTranslating -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF8AB4F8),
                                )
                                Text("翻译中…", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                            }
                        }

                        errorText != null -> {
                            Text(
                                text = errorText.orEmpty(),
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.sp,
                            )
                        }

                        else -> {
                            Text(
                                text = resultText,
                                color = Color(0xFFE8EAED),
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 前台通知 ----------

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (_: Exception) {
            // 权限被撤销时忽略
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "划词翻译",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "划词翻译与剪贴板监听的后台运行通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("无界译 · 运行中", "选中文本或复制文字即可翻译"),
        )
    }

    override fun onDestroy() {
        stopClipboardWatch()
        hideOverlay()
        vmsStore.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val CHANNEL_ID = "stranslate_overlay"
        const val NOTIFICATION_ID = 1001

        const val ACTION_TRANSLATE = "dev.beyonddev.beyondlingo.action.TRANSLATE"
        const val ACTION_DISMISS = "dev.beyonddev.beyondlingo.action.DISMISS"
        const val ACTION_START_CLIPBOARD = "dev.beyonddev.beyondlingo.action.START_CLIPBOARD"
        const val ACTION_STOP_CLIPBOARD = "dev.beyonddev.beyondlingo.action.STOP_CLIPBOARD"
        const val EXTRA_TEXT = "extra_text"

        /** 由无障碍服务 / 剪贴板 watcher 调用 */
        fun requestTranslate(context: Context, text: String, fromSelection: Boolean) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_TRANSLATE
                putExtra(EXTRA_TEXT, text)
                putExtra("from_selection", fromSelection)
            }
            startServiceCompat(context, intent)
        }

        /** 开启/关闭剪贴板监听（由设置页调用） */
        fun setClipboardWatchEnabled(context: Context, enabled: Boolean) {
            val action = if (enabled) ACTION_START_CLIPBOARD else ACTION_STOP_CLIPBOARD
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                this.action = action
            }
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // 后台启动受限时忽略
            }
        }
    }
}
