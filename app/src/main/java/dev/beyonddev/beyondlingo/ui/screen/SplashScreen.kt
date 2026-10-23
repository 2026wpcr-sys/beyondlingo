package dev.beyonddev.beyondlingo.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beyonddev.beyondlingo.Brand
import kotlinx.coroutines.delay

/**
 * 启动页：展示品牌、口号、开发者署名。
 *
 * 停留 [durationMs] 后调用 [onFinish]。
 */
@Composable
fun SplashScreen(
    onFinish: () -> Unit,
    durationMs: Long = 1800L,
) {
    // 初始即为可见：内容不能依赖动画首帧，否则会闪一帧空白
    var visible by remember { mutableStateOf(true) }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500),
        label = "splash-alpha",
    )

    LaunchedEffect(Unit) {
        delay(durationMs)
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B1F3A),
                        Color(0xFF16325C),
                        Color(0xFF1E4B7A),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(alpha)
                .padding(horizontal = 32.dp),
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = Color(0xFF8AB4F8),
                    modifier = Modifier.size(48.dp),
                )
            }

            Spacer(Modifier.height(28.dp))

            // 中文名
            Text(
                text = Brand.APP_NAME_ZH,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 2.sp,
            )

            Spacer(Modifier.height(6.dp))

            // 英文名
            Text(
                text = Brand.APP_NAME_EN,
                fontSize = 15.sp,
                color = Color(0xFF8AB4F8),
                letterSpacing = 1.sp,
            )

            Spacer(Modifier.height(40.dp))

            // 中文口号
            Text(
                text = Brand.SLOGAN_ZH,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            // 英文口号
            Text(
                text = Brand.SLOGAN_EN,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(56.dp))

            // 开发者署名
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "由 ${Brand.AUTHOR_FULL} 开发",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
            }
        }

        // 底部版本号
        Text(
            text = "v${Brand.VERSION}",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .alpha(alpha),
        )
    }
}
