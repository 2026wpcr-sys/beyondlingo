package dev.beyonddev.beyondlingo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.beyonddev.beyondlingo.ui.screen.HistoryScreen
import dev.beyonddev.beyondlingo.ui.screen.OcrScreen
import dev.beyonddev.beyondlingo.ui.screen.SettingsScreen
import dev.beyonddev.beyondlingo.ui.screen.SplashScreen
import dev.beyonddev.beyondlingo.ui.screen.TranslateScreen
import dev.beyonddev.beyondlingo.ui.theme.BeyondLingoTheme
import dev.beyonddev.beyondlingo.vm.HistoryViewModel
import dev.beyonddev.beyondlingo.vm.TranslateViewModel

object Routes {
    const val TRANSLATE = "translate"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val OCR = "ocr"
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 忽略结果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理「系统菜单 → 翻译」传入的选中文本（PROCESS_TEXT / SEND）
        handleIncomingIntent(intent)

        setContent {
            val vm: TranslateViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                requestNotificationPermissionIfNeeded()
            }

            var showSplash by remember {
                // 同一次进程内只展示一次启动页；但从系统菜单进入时跳过启动页
                mutableStateOf(!splashShown && !ExternalTextChannel.hasPending())
            }

            BeyondLingoTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (showSplash) {
                        SplashScreen(
                            onFinish = {
                                splashShown = true
                                showSplash = false
                            }
                        )
                    } else {
                        AppNavHost(vm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * 处理外部传入的文本。
     *
     * 这是最可靠的取词通道：在任何 App 里选中文字 → 系统弹出菜单
     * → 点「无界译」→ 系统通过 ACTION_PROCESS_TEXT 把选中文字传进来。
     * Chrome、微信等所有 App 都支持，不受无障碍事件限制。
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return

        val text = when (action) {
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                } else null
            }
            else -> null
        }

        if (!text.isNullOrBlank()) {
            ExternalTextChannel.put(text)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private companion object {
        /** 进程级标记，避免旋转屏幕后重复播放 */
        var splashShown = false
    }
}

@Composable
private fun AppNavHost(vm: TranslateViewModel) {
    val navController: NavHostController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.TRANSLATE,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                tween(220),
            ) + fadeIn(tween(220))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Left,
                tween(220),
            ) + fadeOut(tween(180))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                tween(220),
            ) + fadeIn(tween(220))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Right,
                tween(220),
            ) + fadeOut(tween(180))
        },
    ) {
        composable(Routes.TRANSLATE) {
            TranslateScreen(
                viewModel = vm,
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },

                onOpenOcr = { navController.navigate(Routes.OCR) },
            )
        }

        composable(Routes.HISTORY) {
            val historyVm: HistoryViewModel = viewModel()
            HistoryScreen(
                viewModel = historyVm,
                onBack = { navController.popBackStack() },
            )
        }


        composable(Routes.OCR) {
            OcrScreen(
                onBack = { navController.popBackStack() },
                onTranslateText = { text ->
                    vm.translateSilently(text)
                    navController.popBackStack(Routes.TRANSLATE, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
