package com.malik.alshurti

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * Runtime performance pose shared by the photoreal fallback, cinematic clips and GLB path.
 * External attention follows eyes -> head -> torso to avoid game-like snapping.
 */
data class CinematicDogPose(
    val breath: Float = 0f,
    val blink: Float = 0f,
    val gazeX: Float = 0f,
    val headYaw: Float = 0f,
    val torsoYaw: Float = 0f,
    val earLeft: Float = 0f,
    val earRight: Float = 0f,
    val jaw: Float = 0f,
    val microShiftX: Float = 0f,
    val microShiftY: Float = 0f
)

@Composable
fun rememberCinematicDogPose(
    phase: CallPhase,
    attention: DogAttention,
    viseme: MouthViseme
): CinematicDogPose {
    val infinite = rememberInfiniteTransition(label = "cinematic-dog-breath")
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    val gazeTarget = when (attention) {
        DogAttention.PHONE -> -1f
        DogAttention.DOOR, DogAttention.STAFF -> 1f
        DogAttention.MONITOR -> -0.42f
        DogAttention.PAPER -> -0.18f
        DogAttention.CAMERA -> 0f
    }
    val gazeX by animateFloatAsState(
        targetValue = gazeTarget,
        animationSpec = tween(105),
        label = "gaze"
    )

    val headYaw by animateFloatAsState(
        targetValue = gazeTarget,
        animationSpec = tween(265, delayMillis = 120, easing = FastOutSlowInEasing),
        label = "head-yaw"
    )

    val torsoYaw by animateFloatAsState(
        targetValue = gazeTarget * 0.55f,
        animationSpec = tween(430, delayMillis = 250, easing = FastOutSlowInEasing),
        label = "torso-yaw"
    )

    val jawTarget = when {
        phase != CallPhase.SPEAKING -> 0f
        viseme == MouthViseme.OPEN -> 1f
        viseme == MouthViseme.WIDE -> 0.68f
        viseme == MouthViseme.ROUND -> 0.56f
        viseme == MouthViseme.CLOSED -> 0.08f
        else -> 0.22f
    }
    val jaw by animateFloatAsState(
        targetValue = jawTarget,
        animationSpec = tween(78),
        label = "jaw"
    )

    val earLeftTarget = when (attention) {
        DogAttention.PHONE -> 0.25f
        DogAttention.DOOR, DogAttention.STAFF -> 0.95f
        DogAttention.MONITOR -> 0.22f
        DogAttention.PAPER -> 0.16f
        DogAttention.CAMERA -> if (phase == CallPhase.LISTENING) 0.42f else 0.12f
    }
    val earRightTarget = when (attention) {
        DogAttention.PHONE -> 0.95f
        DogAttention.DOOR, DogAttention.STAFF -> 0.32f
        DogAttention.MONITOR -> 0.30f
        DogAttention.PAPER -> 0.18f
        DogAttention.CAMERA -> if (phase == CallPhase.LISTENING) 0.48f else 0.16f
    }
    val earLeft by animateFloatAsState(
        targetValue = earLeftTarget,
        animationSpec = tween(235, easing = FastOutSlowInEasing),
        label = "ear-left"
    )
    val earRight by animateFloatAsState(
        targetValue = earRightTarget,
        animationSpec = tween(290, easing = FastOutSlowInEasing),
        label = "ear-right"
    )

    val blink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(Random.nextLong(2_100L, 5_200L))
            blink.animateTo(1f, tween(72))
            blink.animateTo(0f, tween(105))
            if (Random.nextFloat() < 0.18f) {
                delay(80)
                blink.animateTo(0.78f, tween(60))
                blink.animateTo(0f, tween(90))
            }
        }
    }

    val microX = remember { Animatable(0f) }
    val microY = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        while (isActive) {
            delay(Random.nextLong(1_800L, 4_400L))
            val amplitude = if (phase == CallPhase.LISTENING) 0.45f else 1f
            microX.animateTo(Random.nextFloat() * 2f * amplitude - amplitude, tween(850))
            microY.animateTo(Random.nextFloat() * 1.2f * amplitude - 0.6f * amplitude, tween(920))
        }
    }

    return CinematicDogPose(
        breath = breath,
        blink = blink.value,
        gazeX = gazeX,
        headYaw = headYaw,
        torsoYaw = torsoYaw,
        earLeft = earLeft,
        earRight = earRight,
        jaw = jaw,
        microShiftX = microX.value,
        microShiftY = microY.value
    )
}
