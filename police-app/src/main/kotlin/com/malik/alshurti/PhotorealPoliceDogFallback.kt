package com.malik.alshurti

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * High-fidelity visual fallback used only when the licensed rigged GLB is not bundled.
 *
 * It deliberately favors a photoreal cinematic frame over the old illustrated Canvas dog.
 * Micro camera/body movement keeps the scene from reading as a dead wallpaper, while the
 * app still truthfully reserves real jaw/eye/ear/body animation for `police_dog.glb`.
 */
@Composable
fun PhotorealPoliceDogFallback(
    phase: CallPhase,
    attention: DogAttention,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "photoreal-dog-fallback")
    val breathe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fallback-breath"
    )

    val speakingEnergy by animateFloatAsState(
        targetValue = if (phase == CallPhase.SPEAKING) 1f else 0f,
        animationSpec = tween(260),
        label = "fallback-speaking-energy"
    )

    val attentionShift by animateFloatAsState(
        targetValue = when (attention) {
            DogAttention.PHONE -> -1f
            DogAttention.DOOR,
            DogAttention.STAFF -> 1f
            DogAttention.CAMERA -> 0f
        },
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "fallback-attention-shift"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07111C))
    ) {
        Image(
            painter = painterResource(R.drawable.cinematic_office_reference),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Sub-pixel-scale breathing and conversational motion: enough to keep
                    // the frame alive without making the still look like a cheap zoom loop.
                    scaleX = 1.018f + breathe * 0.004f + speakingEnergy * 0.002f
                    scaleY = 1.018f + breathe * 0.006f + speakingEnergy * 0.003f
                    translationX = attentionShift * 5.5f
                    translationY = breathe * 1.8f - speakingEnergy * 0.8f
                    rotationZ = attentionShift * 0.10f
                }
        )

        // A restrained filmic grade keeps UI overlays legible and gives the foreground
        // more perceived depth on bright phone displays.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x12000000),
                            Color.Transparent,
                            Color(0x08000000),
                            Color(0x34000000)
                        )
                    )
                )
        )
    }
}
