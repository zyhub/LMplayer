package com.lm.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lm.player.R
import com.lm.player.core.designsystem.theme.AppleRed

@Composable
fun SplashScreenView(
    visible: Boolean,
    onSplashFinished: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)) +
               scaleOut(targetScale = 1.08f, animationSpec = tween(400, easing = FastOutSlowInEasing))
    ) {
        var startAnimation by remember { mutableStateOf(false) }

        val logoScale by animateFloatAsState(
            targetValue = if (startAnimation) 1f else 0.75f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            label = "logoScale"
        )

        val logoAlpha by animateFloatAsState(
            targetValue = if (startAnimation) 1f else 0f,
            animationSpec = tween(durationMillis = 600, easing = LinearEasing),
            label = "logoAlpha"
        )

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        LaunchedEffect(Unit) {
            startAnimation = true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F0F12),
                            Color(0xFF18181F),
                            Color(0xFF0A0A0D)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 背景柔光环境光斑
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                AppleRed.copy(alpha = 0.25f),
                                Color(0xFF5856D6).copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 30.dp)
            ) {
                // LOGO 容器
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .size(110.dp)
                        .scale(logoScale)
                        .alpha(logoAlpha)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "App Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(26.dp))
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // App 名称与标识
                Text(
                    text = "LMPlayer",
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = Color.White
                    ),
                    modifier = Modifier.alpha(logoAlpha)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "至臻无损 · 极速起播",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 2.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.alpha(logoAlpha)
                )
            }

            // 底部版本标识
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .alpha(logoAlpha)
            ) {
                Text(
                    text = "v1.1.0 • Hi-Fi Automotive Edition",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f),
                        letterSpacing = 0.8.sp
                    )
                )
            }
        }
    }
}
