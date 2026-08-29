package com.vibe.app.presentation.ui.police

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin

@Composable
fun PoliceDogStage(
    mood: DogMood,
    phase: CallPhase,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "police-dog")
    val breathe by transition.animateFloat(
        initialValue = -2f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )
    val blink by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3600
                0f at 0
                0f at 3150
                1f at 3250
                0f at 3360
                0f at 3600
            }
        ),
        label = "blink"
    )
    val talk by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(135, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "talk"
    )
    val earTwitch by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ear"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF07111C),
                        Color(0xFF0D1D2A),
                        Color(0xFF152C37),
                        Color(0xFF09131C)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x55F7C97B), Color.Transparent),
                    center = Offset(w * 0.18f, h * 0.26f),
                    radius = w * 0.62f
                ),
                radius = w * 0.62f,
                center = Offset(w * 0.18f, h * 0.26f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x554BA4C7), Color.Transparent),
                    center = Offset(w * 0.82f, h * 0.30f),
                    radius = w * 0.55f
                ),
                radius = w * 0.55f,
                center = Offset(w * 0.82f, h * 0.30f)
            )

            repeat(4) { index ->
                val left = w * (0.04f + index * 0.245f)
                drawRoundRect(
                    color = Color(0x221E87A7),
                    topLeft = Offset(left, h * 0.12f),
                    size = Size(w * 0.20f, h * 0.34f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                    style = Stroke(width = 2f)
                )
            }

            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF5A3B27), Color(0xFF281B15))),
                topLeft = Offset(-w * 0.05f, h * 0.73f),
                size = Size(w * 1.1f, h * 0.31f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(34f, 34f)
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color(0x33E7B56E), Color.Transparent)),
                topLeft = Offset(0f, h * 0.72f),
                size = Size(w, h * 0.03f)
            )

            val bodyCenter = Offset(w * 0.50f, h * 0.65f + breathe)
            val headCenter = Offset(w * 0.50f, h * 0.42f + breathe * 0.65f)
            val headW = w * 0.43f
            val headH = h * 0.29f

            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF294A5D), Color(0xFF102733), Color(0xFF081921)),
                    center = Offset(bodyCenter.x - w * 0.06f, bodyCenter.y - h * 0.07f),
                    radius = w * 0.37f
                ),
                topLeft = Offset(bodyCenter.x - w * 0.28f, bodyCenter.y - h * 0.17f),
                size = Size(w * 0.56f, h * 0.31f)
            )

            val collar = Path().apply {
                moveTo(w * 0.37f, h * 0.56f + breathe)
                lineTo(w * 0.50f, h * 0.66f + breathe)
                lineTo(w * 0.63f, h * 0.56f + breathe)
                lineTo(w * 0.58f, h * 0.72f + breathe)
                lineTo(w * 0.42f, h * 0.72f + breathe)
                close()
            }
            drawPath(collar, Color(0xFF142F3D))

            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFBC8B58), Color(0xFF7A5030), Color(0xFF3F271A)),
                    center = Offset(headCenter.x - headW * 0.18f, headCenter.y - headH * 0.20f),
                    radius = headW * 0.70f
                ),
                topLeft = Offset(headCenter.x - headW / 2f, headCenter.y - headH / 2f),
                size = Size(headW, headH)
            )

            val leftEar = Path().apply {
                moveTo(headCenter.x - headW * 0.33f, headCenter.y - headH * 0.27f)
                lineTo(headCenter.x - headW * (0.50f + earTwitch * 0.008f), headCenter.y - headH * 0.63f)
                lineTo(headCenter.x - headW * 0.13f, headCenter.y - headH * 0.42f)
                close()
            }
            val rightEar = Path().apply {
                moveTo(headCenter.x + headW * 0.33f, headCenter.y - headH * 0.27f)
                lineTo(headCenter.x + headW * (0.50f - earTwitch * 0.008f), headCenter.y - headH * 0.63f)
                lineTo(headCenter.x + headW * 0.13f, headCenter.y - headH * 0.42f)
                close()
            }
            drawPath(leftEar, Brush.linearGradient(listOf(Color(0xFF4A2A1D), Color(0xFF9B6741))))
            drawPath(rightEar, Brush.linearGradient(listOf(Color(0xFF9B6741), Color(0xFF4A2A1D))))

            drawOval(
                color = Color(0xFF071D2A),
                topLeft = Offset(headCenter.x - headW * 0.34f, headCenter.y - headH * 0.51f),
                size = Size(headW * 0.68f, headH * 0.20f)
            )
            drawRoundRect(
                color = Color(0xFF0B2E40),
                topLeft = Offset(headCenter.x - headW * 0.26f, headCenter.y - headH * 0.62f),
                size = Size(headW * 0.52f, headH * 0.20f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f)
            )
            drawCircle(Color(0xFFE4B353), radius = headW * 0.055f, center = Offset(headCenter.x, headCenter.y - headH * 0.52f))
            drawCircle(Color(0xFF102C3A), radius = headW * 0.025f, center = Offset(headCenter.x, headCenter.y - headH * 0.52f))

            val serious = mood == DogMood.SERIOUS
            val thinking = mood == DogMood.THINKING
            val eyeY = headCenter.y - headH * 0.07f
            val eyeGap = headW * 0.16f
            val eyeHeight = headH * 0.055f * (1f - blink * 0.88f)
            drawOval(
                color = Color(0xFFF2D29B),
                topLeft = Offset(headCenter.x - eyeGap - headW * 0.055f, eyeY - eyeHeight / 2f),
                size = Size(headW * 0.11f, eyeHeight.coerceAtLeast(2f))
            )
            drawOval(
                color = Color(0xFFF2D29B),
                topLeft = Offset(headCenter.x + eyeGap - headW * 0.055f, eyeY - eyeHeight / 2f),
                size = Size(headW * 0.11f, eyeHeight.coerceAtLeast(2f))
            )
            if (blink < 0.8f) {
                val lookShift = if (thinking) {
                    sin((earTwitch * 1.5f).toDouble()).toFloat() * headW * 0.012f
                } else {
                    0f
                }
                drawCircle(Color(0xFF18130F), headW * 0.025f, Offset(headCenter.x - eyeGap + lookShift, eyeY))
                drawCircle(Color(0xFF18130F), headW * 0.025f, Offset(headCenter.x + eyeGap + lookShift, eyeY))
                drawCircle(Color.White.copy(alpha = 0.85f), headW * 0.008f, Offset(headCenter.x - eyeGap - headW * 0.007f + lookShift, eyeY - headH * 0.01f))
                drawCircle(Color.White.copy(alpha = 0.85f), headW * 0.008f, Offset(headCenter.x + eyeGap - headW * 0.007f + lookShift, eyeY - headH * 0.01f))
            }
            if (serious) {
                drawLine(Color(0xFF3A2418), Offset(headCenter.x - headW * 0.24f, eyeY - headH * 0.09f), Offset(headCenter.x - headW * 0.09f, eyeY - headH * 0.04f), 7f)
                drawLine(Color(0xFF3A2418), Offset(headCenter.x + headW * 0.24f, eyeY - headH * 0.09f), Offset(headCenter.x + headW * 0.09f, eyeY - headH * 0.04f), 7f)
            }

            drawOval(
                brush = Brush.radialGradient(listOf(Color(0xFFD5AE7A), Color(0xFF8B5E39))),
                topLeft = Offset(headCenter.x - headW * 0.22f, headCenter.y + headH * 0.02f),
                size = Size(headW * 0.44f, headH * 0.30f)
            )
            drawOval(
                brush = Brush.radialGradient(listOf(Color(0xFF2A2522), Color(0xFF050505))),
                topLeft = Offset(headCenter.x - headW * 0.085f, headCenter.y + headH * 0.02f),
                size = Size(headW * 0.17f, headH * 0.085f)
            )

            val isSpeaking = phase == CallPhase.SPEAKING
            val smile = mood == DogMood.SMILE
            val mouthOpen = if (isSpeaking) talk else if (smile) 0.24f else 0.05f
            val mouthTop = headCenter.y + headH * 0.17f
            drawOval(
                color = Color(0xFF24100D),
                topLeft = Offset(headCenter.x - headW * 0.10f, mouthTop),
                size = Size(headW * 0.20f, headH * (0.025f + 0.095f * mouthOpen))
            )
            if (isSpeaking && mouthOpen > 0.38f) {
                drawOval(
                    color = Color(0xFFC85C61),
                    topLeft = Offset(headCenter.x - headW * 0.055f, mouthTop + headH * 0.025f),
                    size = Size(headW * 0.11f, headH * 0.04f * mouthOpen)
                )
            }

            drawCircle(Color(0xFFDDB355), radius = w * 0.033f, center = Offset(w * 0.59f, h * 0.66f + breathe))
            drawCircle(Color(0xFF102C3A), radius = w * 0.015f, center = Offset(w * 0.59f, h * 0.66f + breathe))

            drawOval(
                color = Color(0xFF8A5B38),
                topLeft = Offset(w * 0.26f, h * 0.69f + breathe),
                size = Size(w * 0.18f, h * 0.075f)
            )
            drawOval(
                color = Color(0xFF8A5B38),
                topLeft = Offset(w * 0.56f, h * 0.69f + breathe),
                size = Size(w * 0.18f, h * 0.075f)
            )
        }
    }
}
