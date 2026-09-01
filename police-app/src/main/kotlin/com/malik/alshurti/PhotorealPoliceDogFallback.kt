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
 * Photoreal master-frame fallback with visibly living office motion.
 *
 * No MP4 deck and no finite scene loop are used. Motion is continuous/non-periodic and deliberately
 * large enough to remain visible on a phone display while the source character image stays intact.
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
    val cameraScale = remember { Animatable(1.055f) }
    val roomLight = remember { Animatable(0.10f) }
    val monitorGlow = remember { Animatable(0.12f) }
    val shadowProgress = remember { Animatable(-0.28f) }
    val shadowAlpha = remember { Animatable(0f) }
    val eventPulse = remember { Animatable(0f) }

    // Immediate visible camera life. Destinations and durations are randomized, never looped.
    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x13579BL)
        while (isActive) {
            cameraX.animateTo(
                targetValue = random.nextInt(-22, 23).toFloat(),
                animationSpec = tween(random.nextInt(2_000, 4_800), easing = LinearEasing)
            )
            delay(random.nextLong(120L, 650L))
        }
    }
    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x2468ACL)
        while (isActive) {
            cameraY.animateTo(
                targetValue = random.nextInt(-14, 15).toFloat(),
                animationSpec = tween(random.nextInt(2_300, 5_200), easing = LinearEasing)
            )
            delay(random.nextLong(180L, 750L))
        }
    }

    // Phase-aware push/pull makes speaking and listening visibly different.
    LaunchedEffect(phase) {
        val target = when (phase) {
            CallPhase.SPEAKING -> 1.095f
            CallPhase.THINKING -> 1.075f
            CallPhase.LISTENING -> 1.052f
            CallPhase.ERROR -> 1.082f
            CallPhase.STARTING -> 1.062f
        }
        cameraScale.animateTo(target, animationSpec = tween(900, easing = LinearEasing))
    }

    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x112233L)
        while (isActive) {
            roomLight.animateTo(
                targetValue = 0.06f + random.nextFloat() * 0.13f,
                animationSpec = tween(random.nextInt(900, 2_400), easing = LinearEasing)
            )
            monitorGlow.animateTo(
                targetValue = 0.07f + random.nextFloat() * 0.16f,
                animationSpec = tween(random.nextInt(650, 1_800), easing = LinearEasing)
            )
            delay(random.nextLong(120L, 700L))
        }
    }

    // A staff silhouette crosses within the first two seconds, then irregularly every few seconds.
    LaunchedEffect(Unit) {
        val random = Random(System.nanoTime() xor 0x445566L)
        delay(900L)
        while (isActive) {
            shadowProgress.snapTo(-0.28f)
            shadowAlpha.snapTo(0f)
            shadowAlpha.animateTo(
                targetValue = 0.16f + random.nextFloat() * 0.10f,
                animationSpec = tween(random.nextInt(180, 340))
            )
            shadowProgress.animateTo(
                targetValue = 1.18f,
                animationSpec = tween(random.nextInt(1_250, 2_250), easing = LinearEasing)
            )
            shadowAlpha.animateTo(0f, animationSpec = tween(random.nextInt(180, 380)))
            delay(random.nextLong(3_200L, 7_800L))
        }
    }

    // Phone/door/staff attention produces an unmistakable one-shot practical-light event.
    LaunchedEffect(attention) {
        if (attention in setOf(DogAttention.PHONE, DogAttention.DOOR, DogAttention.STAFF)) {
            eventPulse.snapTo(0f)
            eventPulse.animateTo(1f, animationSpec = tween(220))
            eventPulse.animateTo(0.18f, animationSpec = tween(820))
            eventPulse.animateTo(0f, animationSpec = tween(1_200))
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
            drawRect(
                color = phaseWash.copy(alpha = roomLight.value * 0.42f),
                size = size
            )

            // Monitor glow remains continuously alive.
            drawCircle(
                color = Color(0xFF8EDCFF).copy(alpha = monitorGlow.value),
                radius = size.minDimension * 0.23f,
                center = Offset(size.width * 0.72f, size.height * 0.30f)
            )

            when (attention) {
                DogAttention.PHONE -> {
                    drawCircle(
                        color = Color(0xFFFFC86A).copy(alpha = 0.14f + eventPulse.value * 0.28f),
                        radius = size.minDimension * (0.12f + eventPulse.value * 0.035f),
                        center = Offset(size.width * 0.22f, size.height * 0.62f)
                    )
                }
                DogAttention.DOOR,
                DogAttention.STAFF -> {
                    val width = size.width * (0.10f + eventPulse.value * 0.16f)
                    drawRect(
                        color = Color(0xFFFFE2B8).copy(alpha = 0.10f + eventPulse.value * 0.20f),
                        topLeft = Offset(size.width - width, size.height * 0.04f),
                        size = Size(width, size.height * 0.88f)
                    )
                }
                else -> Unit
            }

            // Foreground passer: wide and visible enough to read as office activity on a phone.
            val shadowX = size.width * shadowProgress.value
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = shadowAlpha.value),
                        Color.Black.copy(alpha = shadowAlpha.value * 0.72f),
                        Color.Transparent
                    ),
                    startX = shadowX,
                    endX = shadowX + size.width * 0.20f
                ),
                topLeft = Offset(shadowX, size.height * 0.04f),
                size = Size(size.width * 0.20f, size.height * 0.86f)
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x06000000),
                            Color.Transparent,
                            Color(0x05000000),
                            Color(0x20000000)
                        )
                    )
                )
        )
    }
}
