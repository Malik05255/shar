package com.malik.alshurti

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/**
 * Crash-safe cinematic master-frame fallback.
 *
 * Important quality rule: full-body motion is never faked by zooming, translating or rotating
 * this bitmap. If an AI/3D motion asset is unavailable, the exact master frame stays still.
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07111C))
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x10000000),
                            Color.Transparent,
                            Color(0x08000000),
                            Color(0x30000000)
                        )
                    )
                )
        )
    }
}
