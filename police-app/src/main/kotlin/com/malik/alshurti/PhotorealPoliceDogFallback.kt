package com.malik.alshurti

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * Photoreal master-frame fallback with a living OFFICE around it.
 *
 * The character bitmap is never given fake skeletal motion. Instead, this fallback removes the
 * previous dead-screen feeling with non-periodic camera drift, practical-light variation, monitor
 * spill and occasional passing office shadows. Every motion chooses a new random destination and
 * duration, so there is no finite scene deck or visible clip loop to replay.
 */
@Composable
fun PhotorealPoliceDogFallback(
    phase: CallPhase,
    attention: DogAttention,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val image = remember(context) {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.cinematic_office_reference)
                ?.asImageBitmap()
        }.getOrNull()
    }

    if (image == null) {
        val mood = when (phase) {
            CallPhase.LISTENING -> DogMood.LISTENING
            CallPhase.THINKING -> DogMood.THINKING
            CallPhase.SPEAKING -> DogMood.TALKING
            CallPhase.ERROR -> DogMood.SERIOUS
            CallPhase.STARTING -> DogMood.CALM
        }
        PoliceDogStage(mood = mood, phase = phase, viseme = MouthViseme.REST, modifier = modifier)
        return
    }

    val cameraX = remember { Animatable(0f) }
    val cameraY = remember { Animatable(0f) }
    val cameraScale = remember { Animatable(1.035f) }
    val roomLight = remember { Animatable(0.08f) }
    val monitorGlow = remember { Animatable(0.07f) }
    val shadowProgress = remember { Animatable(-0.30f) }
    val shadowAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x13579BL)
        while (isActive) {
            cameraX.animateTo(
                targetValue = random.nextInt(-7, 8).toFloat(),
                animationSpec = tween(random.nextInt(3_400, 7_600), easing = LinearEasing)
            )
            delay(random.nextLong(500L, 1_900L))
        }
    }
    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x2468ACL)
        while (isActive) {
            cameraY.animateTo(
                targetValue = random.nextInt(-5, 6).toFloat(),
                animationSpec = tween(random.nextInt(3_700, 8_400), easing = LinearEasing)
            )
            delay(random.nextLong(700L, 2_400L))
        }
    }
    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x77AA55L)
        while (isActive) {
            cameraScale.animateTo(
                targetValue = 1.028f + random.nextFloat() * 0.026f,
                animationSpec = tween(random.nextInt(5_000, 10_500), easing = LinearEasing)
            )
            delay(random.nextLong(900L, 2_800L))
        }
    }
    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x112233L)
        while (isActive) {
            roomLight.animateTo(
                targetValue = 0.035f + random.nextFloat() * 0.085f,
                animationSpec = tween(random.nextInt(1_700, 4_900), easing = LinearEasing)
            )
            monitorGlow.animateTo(
                targetValue = 0.035f + random.nextFloat() * 0.075f,
                animationSpec = tween(random.nextInt(900, 2_600), easing = LinearEasing)
            )
            delay(random.nextLong(350L, 1_700L))
        }
    }
    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x445566L)
        while (isActive) {
            delay(random.nextLong(8_000L, 21_000L))
            shadowProgress.snapTo(-0.30f)
            shadowAlpha.snapTo(0f)
            shadowAlpha.animateTo(
                targetValue = 0.08f + random.nextFloat() * 0.055f,
                animationSpec = tween(random.nextInt(220, 520))
            )
            shadowProgress.animateTo(
                targetValue = 1.20f,
                animationSpec = tween(random.nextInt(1_800, 3_600), easing = LinearEasing)
            )
            shadowAlpha.animateTo(0f, animationSpec = tween(random.nextInt(280, 620)))
        }
    }

    val phaseWash = when (phase) {
        CallPhase.LISTENING -> Color(0xFF0C7EA8)
        CallPhase.THINKING -> Color(0xFF34558C)
        CallPhase.SPEAKING -> Color(0xFFB56A34)
        CallPhase.ERROR -> Color(0xFF8E2A2A)
        CallPhase.STARTING -> Color(0xFF234A68)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07111C))
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = cameraScale.value
                    scaleY = cameraScale.value
                    translationX = cameraX.value
                    translationY = cameraY.value
                }
        )

        Canvas(Modifier.fillMaxSize()) {
            // Practical room-light breathing: intentionally small so the source image stays real.
            drawRect(
                color = phaseWash.copy(alpha = roomLight.value * 0.34f),
                size = size
            )

            // Monitor spill, positioned as broad environmental light instead of a UI widget.
            drawCircle(
                color = Color(0xFF8EDCFF).copy(alpha = monitorGlow.value),
                radius = size.minDimension * 0.19f,
                center = Offset(size.width * 0.72f, size.height * 0.30f)
            )

            // Context-responsive practical lights make office events visibly different.
            when (attention) {
                DogAttention.PHONE -> drawCircle(
                    color = Color(0xFFFFD58B).copy(alpha = 0.10f + monitorGlow.value * 0.55f),
                    radius = size.minDimension * 0.13f,
                    center = Offset(size.width * 0.22f, size.height * 0.62f)
                )
                DogAttention.DOOR,
                DogAttention.STAFF -> drawRect(
                    color = Color(0xFFFFE2B8).copy(alpha = 0.075f + roomLight.value * 0.55f),
                    topLeft = Offset(size.width * 0.78f, size.height * 0.06f),
                    size = Size(size.width * 0.22f, size.height * 0.84f)
                )
                else -> Unit
            }

            // A soft foreground shadow crosses at irregular intervals to imply staff movement
            // beyond the camera plane without pretending the dog bitmap itself is animated.
            val shadowX = size.width * shadowProgress.value
            drawRect(
                color = Color.Black.copy(alpha = shadowAlpha.value),
                topLeft = Offset(shadowX, size.height * 0.08f),
                size = Size(size.width * 0.13f, size.height * 0.78f)
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x08000000),
                            Color.Transparent,
                            Color(0x07000000),
                            Color(0x28000000)
                        )
                    )
                )
        )
    }
}
