package com.malik.alshurti

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin

/**
 * Lightweight cinematic fallback character.
 *
 * The goal here is deliberately closer to a high-end 3D animated-film frame than
 * a flat mascot: stronger depth, realistic canine proportions, directional light,
 * facial mask, wet nose highlights and softer micro-motion. It remains a Canvas
 * renderer so the APK stays small until the final rigged GLB/Filament character is
 * ready.
 */
@Composable
fun PoliceDogStage(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "cinematic-police-dog")

    val breathe by transition.animateFloat(
        initialValue = -1.8f,
        targetValue = 3.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    val blink by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4300
                0f at 0
                0f at 3610
                1f at 3710
                0f at 3820
                0f at 4300
            }
        ),
        label = "blink"
    )

    val headSway by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "head-sway"
    )

    val earTwitch by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ear-twitch"
    )

    val syllablePulse by transition.animateFloat(
        initialValue = 0.84f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(95, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "syllable-pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF08131B),
                        Color(0xFF10242E),
                        Color(0xFF162C34),
                        Color(0xFF09151D)
                    )
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // --- Cinematic police-office background ---
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x669BCBE0), Color.Transparent),
                    center = Offset(w * 0.80f, h * 0.24f),
                    radius = w * 0.56f
                ),
                center = Offset(w * 0.80f, h * 0.24f),
                radius = w * 0.56f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x55E3B26D), Color.Transparent),
                    center = Offset(w * 0.14f, h * 0.30f),
                    radius = w * 0.44f
                ),
                center = Offset(w * 0.14f, h * 0.30f),
                radius = w * 0.44f
            )

            // Soft-focus practical lights / bokeh.
            listOf(
                Offset(w * 0.13f, h * 0.20f) to 0.022f,
                Offset(w * 0.89f, h * 0.18f) to 0.018f,
                Offset(w * 0.82f, h * 0.42f) to 0.014f,
                Offset(w * 0.18f, h * 0.47f) to 0.012f
            ).forEachIndexed { index, (center, radius) ->
                drawCircle(
                    color = if (index % 2 == 0) Color(0x55F4CA84) else Color(0x554BB6DA),
                    center = center,
                    radius = w * radius
                )
            }

            // Rear glass / acoustic panels.
            repeat(3) { index ->
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0x221D6B7F), Color(0x0812252D))
                    ),
                    topLeft = Offset(w * (0.08f + index * 0.30f), h * 0.11f),
                    size = Size(w * 0.25f, h * 0.34f),
                    cornerRadius = CornerRadius(22f, 22f),
                    style = Stroke(width = 2f)
                )
            }

            // Desk, with a glossy top line.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF6C4A34), Color(0xFF35251D), Color(0xFF181311))
                ),
                topLeft = Offset(-w * 0.04f, h * 0.755f),
                size = Size(w * 1.08f, h * 0.28f),
                cornerRadius = CornerRadius(34f, 34f)
            )
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x66EFC888), Color(0x11000000), Color.Transparent)
                ),
                topLeft = Offset(0f, h * 0.748f),
                size = Size(w, h * 0.022f)
            )

            // --- Character positioning ---
            val swayX = headSway * w * 0.0045f
            val bodyCenter = Offset(w * 0.50f, h * 0.665f + breathe)
            val headCenter = Offset(w * 0.50f + swayX, h * 0.405f + breathe * 0.55f)
            val headW = w * 0.46f
            val headH = h * 0.31f

            // Cast shadow behind the character gives the frame more 3D separation.
            drawOval(
                color = Color.Black.copy(alpha = 0.26f),
                topLeft = Offset(w * 0.24f, h * 0.49f + breathe + 10f),
                size = Size(w * 0.52f, h * 0.30f)
            )

            // Uniform torso with directional highlight.
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF315B6B),
                        Color(0xFF15333F),
                        Color(0xFF081C24)
                    ),
                    center = Offset(bodyCenter.x - w * 0.10f, bodyCenter.y - h * 0.12f),
                    radius = w * 0.43f
                ),
                topLeft = Offset(bodyCenter.x - w * 0.30f, bodyCenter.y - h * 0.18f),
                size = Size(w * 0.60f, h * 0.33f)
            )

            // Shirt seam / collar, more tailored than the former flat oval.
            val leftCollar = Path().apply {
                moveTo(w * 0.37f, h * 0.555f + breathe)
                lineTo(w * 0.49f, h * 0.645f + breathe)
                lineTo(w * 0.43f, h * 0.705f + breathe)
                lineTo(w * 0.34f, h * 0.60f + breathe)
                close()
            }
            val rightCollar = Path().apply {
                moveTo(w * 0.63f, h * 0.555f + breathe)
                lineTo(w * 0.51f, h * 0.645f + breathe)
                lineTo(w * 0.57f, h * 0.705f + breathe)
                lineTo(w * 0.66f, h * 0.60f + breathe)
                close()
            }
            drawPath(leftCollar, Color(0xFF0D2731))
            drawPath(rightCollar, Color(0xFF0D2731))
            drawLine(
                color = Color(0x335CB0C3),
                start = Offset(w * 0.50f, h * 0.64f + breathe),
                end = Offset(w * 0.50f, h * 0.74f + breathe),
                strokeWidth = 2.5f
            )

            // Badge.
            drawCircle(Color(0xFFE3BF69), w * 0.030f, Offset(w * 0.61f, h * 0.655f + breathe))
            drawCircle(Color(0xFF294656), w * 0.015f, Offset(w * 0.61f, h * 0.655f + breathe))
            drawCircle(Color(0xFFE7C978), w * 0.005f, Offset(w * 0.61f, h * 0.655f + breathe))

            // Neck fur.
            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF9A6A43), Color(0xFF634027), Color(0xFF2F211A))
                ),
                topLeft = Offset(w * 0.36f, h * 0.51f + breathe),
                size = Size(w * 0.28f, h * 0.18f)
            )

            // --- Ears: tall Malinois / shepherd silhouette ---
            val leftEar = Path().apply {
                moveTo(headCenter.x - headW * 0.34f, headCenter.y - headH * 0.20f)
                lineTo(
                    headCenter.x - headW * (0.42f + earTwitch * 0.007f),
                    headCenter.y - headH * 0.63f
                )
                lineTo(headCenter.x - headW * 0.12f, headCenter.y - headH * 0.38f)
                close()
            }
            val rightEar = Path().apply {
                moveTo(headCenter.x + headW * 0.34f, headCenter.y - headH * 0.20f)
                lineTo(
                    headCenter.x + headW * (0.42f - earTwitch * 0.007f),
                    headCenter.y - headH * 0.63f
                )
                lineTo(headCenter.x + headW * 0.12f, headCenter.y - headH * 0.38f)
                close()
            }
            drawPath(
                leftEar,
                Brush.linearGradient(listOf(Color(0xFF2F211A), Color(0xFFA66F47), Color(0xFF7A4E31)))
            )
            drawPath(
                rightEar,
                Brush.linearGradient(listOf(Color(0xFF7A4E31), Color(0xFFA66F47), Color(0xFF2F211A)))
            )

            // Inner ear gives depth.
            val leftInnerEar = Path().apply {
                moveTo(headCenter.x - headW * 0.33f, headCenter.y - headH * 0.27f)
                lineTo(headCenter.x - headW * 0.39f, headCenter.y - headH * 0.53f)
                lineTo(headCenter.x - headW * 0.18f, headCenter.y - headH * 0.34f)
                close()
            }
            val rightInnerEar = Path().apply {
                moveTo(headCenter.x + headW * 0.33f, headCenter.y - headH * 0.27f)
                lineTo(headCenter.x + headW * 0.39f, headCenter.y - headH * 0.53f)
                lineTo(headCenter.x + headW * 0.18f, headCenter.y - headH * 0.34f)
                close()
            }
            drawPath(leftInnerEar, Color(0xFF51332B).copy(alpha = 0.78f))
            drawPath(rightInnerEar, Color(0xFF51332B).copy(alpha = 0.78f))

            // Head base: warmer film-animation fur, with darker perimeter.
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFD5A16B),
                        Color(0xFFA96D43),
                        Color(0xFF6A432D),
                        Color(0xFF34231D)
                    ),
                    center = Offset(headCenter.x - headW * 0.15f, headCenter.y - headH * 0.20f),
                    radius = headW * 0.72f
                ),
                topLeft = Offset(headCenter.x - headW * 0.50f, headCenter.y - headH * 0.48f),
                size = Size(headW, headH * 0.91f)
            )

            // Characteristic dark face mask, making the dog feel like a real Malinois.
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xCC3B2B25), Color(0x88312622), Color.Transparent),
                    center = Offset(headCenter.x, headCenter.y + headH * 0.04f),
                    radius = headW * 0.38f
                ),
                topLeft = Offset(headCenter.x - headW * 0.31f, headCenter.y - headH * 0.18f),
                size = Size(headW * 0.62f, headH * 0.58f)
            )

            // Forehead highlight and cheek volume.
            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color(0x55F3C58F), Color.Transparent)
                ),
                topLeft = Offset(headCenter.x - headW * 0.31f, headCenter.y - headH * 0.34f),
                size = Size(headW * 0.40f, headH * 0.35f)
            )
            drawOval(
                color = Color(0x229B6A45),
                topLeft = Offset(headCenter.x - headW * 0.43f, headCenter.y + headH * 0.00f),
                size = Size(headW * 0.20f, headH * 0.30f)
            )
            drawOval(
                color = Color(0x224F3024),
                topLeft = Offset(headCenter.x + headW * 0.23f, headCenter.y + headH * 0.01f),
                size = Size(headW * 0.20f, headH * 0.30f)
            )

            // --- Eyes: smaller, deeper, more canine ---
            val serious = mood == DogMood.SERIOUS
            val thinking = mood == DogMood.THINKING
            val listening = mood == DogMood.LISTENING || phase == CallPhase.LISTENING
            val smiling = mood == DogMood.SMILE
            val eyeY = headCenter.y - headH * 0.075f
            val eyeGap = headW * 0.165f
            val eyeH = headH * 0.052f * (1f - blink * 0.90f)
            val eyeW = headW * 0.105f

            val gazeShift = when {
                thinking -> sin((headSway * 1.8f).toDouble()).toFloat() * headW * 0.020f
                listening -> headSway * headW * 0.006f
                else -> 0f
            }

            // Eyelid / socket shadow.
            drawOval(
                color = Color(0xAA241B18),
                topLeft = Offset(headCenter.x - eyeGap - eyeW * 0.60f, eyeY - headH * 0.050f),
                size = Size(eyeW * 1.20f, headH * 0.10f)
            )
            drawOval(
                color = Color(0xAA241B18),
                topLeft = Offset(headCenter.x + eyeGap - eyeW * 0.60f, eyeY - headH * 0.050f),
                size = Size(eyeW * 1.20f, headH * 0.10f)
            )

            drawOval(
                color = Color(0xFFE5C47B),
                topLeft = Offset(headCenter.x - eyeGap - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH.coerceAtLeast(2f))
            )
            drawOval(
                color = Color(0xFFE5C47B),
                topLeft = Offset(headCenter.x + eyeGap - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH.coerceAtLeast(2f))
            )

            if (blink < 0.80f) {
                drawCircle(
                    color = Color(0xFF211610),
                    radius = headW * 0.027f,
                    center = Offset(headCenter.x - eyeGap + gazeShift, eyeY)
                )
                drawCircle(
                    color = Color(0xFF211610),
                    radius = headW * 0.027f,
                    center = Offset(headCenter.x + eyeGap + gazeShift, eyeY)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.92f),
                    radius = headW * 0.008f,
                    center = Offset(headCenter.x - eyeGap - headW * 0.008f + gazeShift, eyeY - headH * 0.012f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.92f),
                    radius = headW * 0.008f,
                    center = Offset(headCenter.x + eyeGap - headW * 0.008f + gazeShift, eyeY - headH * 0.012f)
                )
            }

            // Brows / expression.
            val browColor = Color(0x88452C21)
            if (serious) {
                drawLine(
                    browColor,
                    Offset(headCenter.x - headW * 0.25f, eyeY - headH * 0.075f),
                    Offset(headCenter.x - headW * 0.09f, eyeY - headH * 0.035f),
                    7f
                )
                drawLine(
                    browColor,
                    Offset(headCenter.x + headW * 0.25f, eyeY - headH * 0.075f),
                    Offset(headCenter.x + headW * 0.09f, eyeY - headH * 0.035f),
                    7f
                )
            } else if (smiling) {
                drawLine(
                    browColor.copy(alpha = 0.45f),
                    Offset(headCenter.x - headW * 0.24f, eyeY - headH * 0.050f),
                    Offset(headCenter.x - headW * 0.11f, eyeY - headH * 0.065f),
                    5f
                )
                drawLine(
                    browColor.copy(alpha = 0.45f),
                    Offset(headCenter.x + headW * 0.24f, eyeY - headH * 0.050f),
                    Offset(headCenter.x + headW * 0.11f, eyeY - headH * 0.065f),
                    5f
                )
            }

            // --- Longer muzzle for realistic dog proportions ---
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFCA9462), Color(0xFF9B6542), Color(0xFF62412F)),
                    center = Offset(headCenter.x - headW * 0.06f, headCenter.y + headH * 0.13f),
                    radius = headW * 0.30f
                ),
                topLeft = Offset(headCenter.x - headW * 0.235f, headCenter.y + headH * 0.025f),
                size = Size(headW * 0.47f, headH * 0.34f)
            )

            // Dark muzzle bridge.
            drawOval(
                brush = Brush.verticalGradient(
                    listOf(Color(0xAA352820), Color(0x55291F1B), Color.Transparent)
                ),
                topLeft = Offset(headCenter.x - headW * 0.14f, headCenter.y + headH * 0.015f),
                size = Size(headW * 0.28f, headH * 0.20f)
            )

            // Wet nose + specular highlight.
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF393838), Color(0xFF111111), Color(0xFF030303)),
                    center = Offset(headCenter.x - headW * 0.035f, headCenter.y + headH * 0.085f),
                    radius = headW * 0.11f
                ),
                topLeft = Offset(headCenter.x - headW * 0.095f, headCenter.y + headH * 0.065f),
                size = Size(headW * 0.19f, headH * 0.095f)
            )
            drawOval(
                color = Color.White.copy(alpha = 0.40f),
                topLeft = Offset(headCenter.x - headW * 0.050f, headCenter.y + headH * 0.077f),
                size = Size(headW * 0.045f, headH * 0.018f)
            )

            // Whisker dots and subtle whiskers.
            repeat(3) { i ->
                val dy = headH * (0.155f + i * 0.027f)
                drawCircle(Color(0x553E2B24), headW * 0.006f, Offset(headCenter.x - headW * 0.12f, headCenter.y + dy))
                drawCircle(Color(0x553E2B24), headW * 0.006f, Offset(headCenter.x + headW * 0.12f, headCenter.y + dy))
            }
            repeat(3) { i ->
                val y = headCenter.y + headH * (0.17f + i * 0.023f)
                drawLine(
                    Color.White.copy(alpha = 0.18f),
                    Offset(headCenter.x - headW * 0.15f, y),
                    Offset(headCenter.x - headW * (0.30f + i * 0.02f), y + headH * 0.015f),
                    1.2f
                )
                drawLine(
                    Color.White.copy(alpha = 0.18f),
                    Offset(headCenter.x + headW * 0.15f, y),
                    Offset(headCenter.x + headW * (0.30f + i * 0.02f), y + headH * 0.015f),
                    1.2f
                )
            }

            // --- Mouth / expression / visemes ---
            val speaking = phase == CallPhase.SPEAKING
            val shapeWidth = when (viseme) {
                MouthViseme.CLOSED -> 0.13f
                MouthViseme.ROUND -> 0.12f
                MouthViseme.WIDE -> 0.25f
                MouthViseme.OPEN -> 0.19f
                MouthViseme.REST -> if (smiling) 0.23f else 0.16f
            }
            val shapeOpen = when (viseme) {
                MouthViseme.CLOSED -> 0.02f
                MouthViseme.ROUND -> 0.58f
                MouthViseme.WIDE -> 0.28f
                MouthViseme.OPEN -> 0.86f
                MouthViseme.REST -> if (smiling) 0.10f else 0.025f
            }
            val mouthOpen = if (speaking) shapeOpen * syllablePulse else shapeOpen
            val mouthTop = headCenter.y + headH * 0.215f
            val mouthWidth = headW * shapeWidth

            // Smile corners are separate so the dog can visibly grin without looking human.
            if (smiling && !speaking) {
                drawLine(
                    Color(0xAA2A1714),
                    Offset(headCenter.x - headW * 0.055f, mouthTop + headH * 0.010f),
                    Offset(headCenter.x - headW * 0.14f, mouthTop - headH * 0.006f),
                    4.5f
                )
                drawLine(
                    Color(0xAA2A1714),
                    Offset(headCenter.x + headW * 0.055f, mouthTop + headH * 0.010f),
                    Offset(headCenter.x + headW * 0.14f, mouthTop - headH * 0.006f),
                    4.5f
                )
            }

            drawOval(
                color = Color(0xFF1E0D0C),
                topLeft = Offset(headCenter.x - mouthWidth / 2f, mouthTop),
                size = Size(mouthWidth, headH * (0.020f + 0.102f * mouthOpen))
            )

            if ((speaking || smiling) && mouthOpen > 0.34f && viseme != MouthViseme.CLOSED) {
                drawOval(
                    color = Color(0xFFD56A71),
                    topLeft = Offset(headCenter.x - mouthWidth * 0.27f, mouthTop + headH * 0.032f),
                    size = Size(mouthWidth * 0.54f, headH * 0.042f * mouthOpen)
                )
            }

            // Tiny lower-jaw shadow makes speaking read more naturally.
            if (speaking && mouthOpen > 0.42f) {
                drawOval(
                    color = Color.Black.copy(alpha = 0.12f),
                    topLeft = Offset(headCenter.x - mouthWidth * 0.46f, mouthTop + headH * 0.058f),
                    size = Size(mouthWidth * 0.92f, headH * 0.038f * mouthOpen)
                )
            }

            // Fine fur strokes along cheeks: cheap detail that reads as 3D animation on phone.
            repeat(9) { i ->
                val fraction = i / 8f
                val y = headCenter.y + headH * (0.01f + 0.28f * fraction)
                val spread = headW * (0.39f + fraction * 0.035f)
                drawLine(
                    color = Color(0x33E8B77D),
                    start = Offset(headCenter.x - spread, y),
                    end = Offset(headCenter.x - spread - headW * 0.035f, y + headH * 0.018f),
                    strokeWidth = 1.7f
                )
                drawLine(
                    color = Color(0x33251310),
                    start = Offset(headCenter.x + spread, y),
                    end = Offset(headCenter.x + spread + headW * 0.035f, y + headH * 0.018f),
                    strokeWidth = 1.7f
                )
            }

            // Police cap sits slightly forward, with a glossy brim.
            drawOval(
                brush = Brush.verticalGradient(listOf(Color(0xFF091C24), Color(0xFF041117))),
                topLeft = Offset(headCenter.x - headW * 0.36f, headCenter.y - headH * 0.46f),
                size = Size(headW * 0.72f, headH * 0.15f)
            )
            drawRoundRect(
                brush = Brush.linearGradient(listOf(Color(0xFF143845), Color(0xFF08242D))),
                topLeft = Offset(headCenter.x - headW * 0.25f, headCenter.y - headH * 0.57f),
                size = Size(headW * 0.50f, headH * 0.17f),
                cornerRadius = CornerRadius(20f, 20f)
            )
            drawOval(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(headCenter.x - headW * 0.22f, headCenter.y - headH * 0.535f),
                size = Size(headW * 0.23f, headH * 0.025f)
            )
            drawCircle(Color(0xFFE5BE67), headW * 0.050f, Offset(headCenter.x, headCenter.y - headH * 0.475f))
            drawCircle(Color(0xFF173B48), headW * 0.026f, Offset(headCenter.x, headCenter.y - headH * 0.475f))
            drawCircle(Color(0xFFEAD07B), headW * 0.008f, Offset(headCenter.x, headCenter.y - headH * 0.475f))

            // Forearms / paws on desk, shaded instead of plain tan bars.
            drawOval(
                brush = Brush.linearGradient(listOf(Color(0xFF9F6D49), Color(0xFF61412F))),
                topLeft = Offset(w * 0.245f, h * 0.698f + breathe),
                size = Size(w * 0.21f, h * 0.076f)
            )
            drawOval(
                brush = Brush.linearGradient(listOf(Color(0xFF61412F), Color(0xFF9F6D49))),
                topLeft = Offset(w * 0.545f, h * 0.698f + breathe),
                size = Size(w * 0.21f, h * 0.076f)
            )

            // Contact shadows on desktop.
            drawOval(
                Color.Black.copy(alpha = 0.16f),
                Offset(w * 0.255f, h * 0.748f),
                Size(w * 0.19f, h * 0.020f)
            )
            drawOval(
                Color.Black.copy(alpha = 0.16f),
                Offset(w * 0.555f, h * 0.748f),
                Size(w * 0.19f, h * 0.020f)
            )
        }
    }
}
