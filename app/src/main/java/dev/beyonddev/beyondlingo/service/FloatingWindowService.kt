package dev.beyonddev.beyondlingo.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
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
import dev.beyonddev.beyondlingo.Brand
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
 * 悬浮窗服务：展示划词翻译结果。
 *
 * 由 [SelectionAccessibilityService] 在检测到选中文本时拉起。
 *
 * 用 Compose 渲染内容，通过 [WindowManager] 挂载为
 * `TYPE_APPLICATION_OVERLAY` 悬浮窗。
 *
 * 继承 [LifecycleService] 以获得 Lifecycle / ViewModelStore / SavedStateRegistry
 * 三个 owner（ComposeView 需要它们）。
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

    /** 常驻悬浮球（复制取词入口） */
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** 临时可聚焦窗口：用于拿到焦点后读剪贴板 */
    private var focusProxyView: android.view.View? = null
    private var focusProxyParams: WindowManager.LayoutParams? = null

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
                if (text.isNotBlank()) {
                    showAndTranslate(text)
                } else {
                    stopSelf()
                }
            }
            ACTION_DISMISS -> {
                hideOverlay()
                if (bubbleView == null) stopSelf()
            }
            ACTION_SHOW_BUBBLE -> {
                ensureBubble()
            }
            ACTION_HIDE_BUBBLE -> {
                removeBubble()
                if (composeView == null) stopSelf()
            }
        }
        return START_STICKY
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
            // 权限缺失或窗口被拒
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

    // ---------- 悬浮球（复制取词） ----------

    /**
     * 添加常驻悬浮球。
     *
     * 用户复制文字后点它，立即读取剪贴板并翻译。
     * 这是 Android 上最可靠的取词方式——点击瞬间悬浮窗有焦点，剪贴板可读。
     */
    private fun ensureBubble() {
        if (bubbleView != null) return

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent {
                dev.beyonddev.beyondlingo.ui.overlay.FloatingBubble(
                    onClick = { onBubbleClick() },
                    onDrag = { dx, dy -> moveBubble(dx, dy) },
                )
            }
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
            // 默认贴在屏幕右侧垂直居中偏上
            val dm = resources.displayMetrics
            x = dm.widthPixels - dpToPx(48) - dpToPx(8)
            y = dm.heightPixels / 2 - dpToPx(24)
        }

        bubbleView = view
        bubbleParams = params

        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            bubbleView = null
            bubbleParams = null
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // 已被移除
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    private fun moveBubble(dx: Float, dy: Float) {
        val params = bubbleParams ?: return
        val view = bubbleView ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // 忽略
        }
    }

    /**
     * 点悬浮球：读剪贴板 → 翻译。
     *
     * ## 为什么要先抢焦点
     *
     * Android 10+ 只允许**有焦点**的应用读剪贴板。悬浮球窗口是
     * `FLAG_NOT_FOCUSABLE`，本身没有焦点，直接读会拿到空值并报
     * "Denying clipboard access ... application is not in focus"。
     *
     * 因此这里先临时创建一个可聚焦的透明窗口，等它拿到焦点后再读取，
     * 读完立即移除。这是屏译等应用的通用做法。
     */
    private fun onBubbleClick() {
        // 已有焦点（如从无障碍弹窗进入）时直接读
        if (hasWindowFocusNow()) {
            handleBubbleTranslate()
            return
        }

        acquireFocusThenRead()
    }

    /** 当前是否已有可读剪贴板的焦点 */
    private fun hasWindowFocusNow(): Boolean {
        val text = readClipboard()
        return !text.isNullOrBlank()
    }

    /**
     * 通过临时可聚焦窗口取得焦点后读取剪贴板。
     */
    private fun acquireFocusThenRead() {
        if (focusProxyView != null) return

        val proxy = android.view.View(this)
        val params = WindowManager.LayoutParams(
            1,
            1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // 关键：不加 FLAG_NOT_FOCUSABLE，让窗口可获焦
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        proxy.isFocusable = true
        proxy.isFocusableInTouchMode = true

        proxy.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                mainHandler.postDelayed({
                    removeFocusProxy()
                    handleBubbleTranslate()
                }, FOCUS_SETTLE_MS)
            }
        }

        try {
            windowManager.addView(proxy, params)
            focusProxyView = proxy
            focusProxyParams = params
            proxy.requestFocus()

            // 兜底：2 秒内没拿到焦点也尝试一次，避免卡住
            mainHandler.postDelayed({
                if (focusProxyView != null) {
                    removeFocusProxy()
                    handleBubbleTranslate()
                }
            }, FOCUS_TIMEOUT_MS)
        } catch (_: Exception) {
            focusProxyView = null
            focusProxyParams = null
            // 直接尝试读，可能本来就有焦点
            handleBubbleTranslate()
        }
    }

    private fun removeFocusProxy() {
        focusProxyView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // 已移除
            }
        }
        focusProxyView = null
        focusProxyParams = null
    }

    /** 真正读取剪贴板并翻译 */
    private fun handleBubbleTranslate() {
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            showOverlayWithText("剪贴板是空的，请先复制文字")
            return
        }
        Log.d(TAG, "bubble translate: '${text.take(60)}'")
        showAndTranslate(text)
    }

    /** 读取剪贴板文本；应用此时有焦点，可正常读取 */
    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(this).toString().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** 展示一段纯文本（用于提示） */
    private fun showOverlayWithText(message: String) {
        ensureOverlay()
        sourceText = ""
        resultText = ""
        errorText = message
        isTranslating = false
    }

    /**
     * 仅在悬浮球未常驻时停止服务。
     *
     * 悬浮球开启时服务必须活着，否则球会跟着消失。
     */
    private fun stopSelfIfNoBubble() {
        if (bubbleView == null) stopSelf()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ---------- 翻译 ----------

    private fun showAndTranslate(text: String) {
        Log.d(TAG, "showAndTranslate: '${text.take(40)}'")
        scope.launch {
            val settings = SettingsStore(applicationContext).settings.first()

            // 静默模式：只翻译，不弹窗，结果写通知
            if (settings.silentAutoTranslate) {
                val repo = TranslateRepository(applicationContext)
                Log.d(TAG, "calling translate...")

                val outcome = repo.translateWithCurrentSettings(text, checkCacheFirst = true)

                Log.d(TAG, "translate result: $outcome")
                when (outcome) {
                    is TranslateOutcome.Success ->
                        updateNotification(text.take(40), outcome.text.take(200))
                    is TranslateOutcome.Failure ->
                        updateNotification("翻译失败", outcome.message.take(200))
                }
                stopSelfIfNoBubble()
                return@launch
            }

            // 正常模式：弹悬浮窗
            ensureOverlay()

            // 不要立刻清空结果 —— 否则连续触发时旧内容消失、
            // 新内容出现，会来回闪烁。保留旧译文，只切换「翻译中」标记。
            val isFirstShow = resultText.isBlank() && !isTranslating
            sourceText = text
            errorText = null
            isTranslating = true

            scheduleAutoDismiss(settings.floatingAutoDismiss, settings.floatingAutoDismissMs)

            val repo = TranslateRepository(applicationContext)
            Log.d(TAG, "calling translate...")

            val outcome = repo.translateWithCurrentSettings(text, checkCacheFirst = true)

            Log.d(TAG, "translate result: $outcome")

            when (outcome) {
                is TranslateOutcome.Success -> {
                    resultText = outcome.text
                    errorText = null
                    // 无悬浮窗权限时用通知兜底
                    if (composeView == null) {
                        updateNotification(text.take(40), outcome.text.take(200))
                    }
                }
                is TranslateOutcome.Failure -> {
                    // 已有旧译文时保留旧译文，只在无内容时显示错误
                    if (isFirstShow) {
                        resultText = ""
                        errorText = outcome.message
                    }
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
            // 悬浮球常驻时只关结果窗，不能停服务，否则球也会消失
            if (bubbleView == null) stopSelf()
        }
        autoDismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun copyResult() {
        if (resultText.isBlank()) return
        ClipboardWriter.copy(applicationContext, resultText)
    }

    // ---------- Compose 内容 ----------

    @Composable
    private fun OverlayContent() {
        MaterialTheme {
            dev.beyonddev.beyondlingo.ui.overlay.OverlayContent(
                sourceText = sourceText,
                resultText = resultText,
                isTranslating = isTranslating,
                errorText = errorText,
                onCopy = { copyResult() },
                onClose = {
                    hideOverlay()
                    // 悬浮球仍在常驻时，保留服务；否则停掉
                    if (bubbleView == null) stopSelf()
                },
                onDrag = { dx, dy -> moveOverlay(dx, dy) },
            )
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
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "${Brand.APP_NAME_ZH}的划词翻译结果"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(Brand.APP_NAME_ZH, "正在翻译…"),
        )
    }

    override fun onDestroy() {
        removeFocusProxy()
        removeBubble()
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
        private const val TAG = "BeyondLingoFloat"

        /** 拿到焦点后等待多久再读剪贴板 */
        private const val FOCUS_SETTLE_MS = 120L

        /** 抢焦点超时兜底 */
        private const val FOCUS_TIMEOUT_MS = 2000L
        const val CHANNEL_ID = "beyondlingo_overlay"
        const val NOTIFICATION_ID = 1001

        const val ACTION_TRANSLATE = "dev.beyonddev.beyondlingo.action.TRANSLATE"
        const val ACTION_DISMISS = "dev.beyonddev.beyondlingo.action.DISMISS"
        const val ACTION_SHOW_BUBBLE = "dev.beyonddev.beyondlingo.action.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "dev.beyonddev.beyondlingo.action.HIDE_BUBBLE"
        const val EXTRA_TEXT = "extra_text"

        /** 由无障碍服务调用 */
        fun requestTranslate(context: Context, text: String, fromSelection: Boolean) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_TRANSLATE
                putExtra(EXTRA_TEXT, text)
                putExtra("from_selection", fromSelection)
            }
            startServiceCompat(context, intent)
        }

        /** 显示/隐藏常驻悬浮球（复制取词入口） */
        fun setBubbleVisible(context: Context, visible: Boolean) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = if (visible) ACTION_SHOW_BUBBLE else ACTION_HIDE_BUBBLE
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