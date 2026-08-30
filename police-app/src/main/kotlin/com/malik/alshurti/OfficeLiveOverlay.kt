package com.malik.alshurti

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
 * Adds a living office around either the real GLB renderer or the development fallback.
 * Elements stay close to the frame edges so the dog remains the hero of the scene.
 */
@Composable
fun OfficeLiveOverlay(
    scene: OfficeSceneState,
    phase: CallPhase,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "office-live")
    val staffWalk by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "staff-walk"
    )
    val practicalPulse by infinite.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "practical-pulse"
    )
    val phonePulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(260),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phone-pulse"
    )
    val doorProgress by animateFloatAsState(
        targetValue = if (scene.doorOpen) 1f else 0f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "door-open"
    )
    val doorStaffPresence by animateFloatAsState(
        targetValue = if (scene.staffAtDoor) 1f else 0f,
        animationSpec = tween(420),
        label = "door-staff"
    )

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Subtle cinematic edge lighting.
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    Color(0x221FD3E5),
                    Color.Transparent,
                    Color.Transparent,
                    Color(0x18E0A763)
                )
            ),
            size = size
        )

        // Left-side glass corridor and distant staff movement.
        val corridorLeft = w * 0.03f
        val corridorTop = h * 0.16f
        val corridorWidth = w * 0.20f
        val corridorHeight = h * 0.43f
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color(0x221D6B7F), Color(0x07111A20))
            ),
            topLeft = Offset(corridorLeft, corridorTop),
            size = Size(corridorWidth, corridorHeight),
            cornerRadius = CornerRadius(20f, 20f),
            style = Stroke(width = 1.5f)
        )

        if (scene.staffVisible) {
            val walkX = corridorLeft + corridorWidth * (0.26f + (staffWalk + 1f) * 0.16f)
            val staffY = corridorTop + corridorHeight * 0.65f
            val bob = sin(staffWalk * 3.14159f * 2f) * h * 0.004f
            drawCircle(
                color = Color(0x663C4850),
                center = Offset(walkX, staffY - h * 0.11f + bob),
                radius = w * 0.018f
            )
            drawRoundRect(
                color = Color(0x55313B42),
                topLeft = Offset(walkX - w * 0.022f, staffY - h * 0.085f + bob),
                size = Size(w * 0.044f, h * 0.13f),
                cornerRadius = CornerRadius(18f, 18f)
            )
            drawLine(
                color = Color(0x44313B42),
                start = Offset(walkX - w * 0.012f, staffY + h * 0.045f + bob),
                end = Offset(walkX - w * 0.022f, staffY + h * 0.11f + bob),
                strokeWidth = w * 0.012f
            )
            drawLine(
                color = Color(0x44313B42),
                start = Offset(walkX + w * 0.012f, staffY + h * 0.045f + bob),
                end = Offset(walkX + w * 0.022f, staffY + h * 0.11f + bob),
                strokeWidth = w * 0.012f
            )
        }

        // Right-side office door. The panel narrows as it visually swings open.
        val frameLeft = w * 0.83f
        val frameTop = h * 0.14f
        val frameWidth = w * 0.15f
        val frameHeight = h * 0.51f
        drawRoundRect(
            color = Color(0x9930201A),
            topLeft = Offset(frameLeft, frameTop),
            size = Size(frameWidth, frameHeight),
            cornerRadius = CornerRadius(10f, 10f)
        )
        drawRect(
            color = Color(0xCC080F13),
            topLeft = Offset(frameLeft + w * 0.012f, frameTop + h * 0.014f),
            size = Size(frameWidth - w * 0.024f, frameHeight - h * 0.028f)
        )

        if (doorProgress > 0.03f) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x604A7280), Color(0x20131D22), Color.Black)
                ),
                topLeft = Offset(frameLeft + w * 0.014f, frameTop + h * 0.014f),
                size = Size((frameWidth - w * 0.028f) * doorProgress, frameHeight - h * 0.028f)
            )
        }

        val panelWidth = (frameWidth - w * 0.018f) * (1f - 0.72f * doorProgress)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF3E2A21), Color(0xFF211712))
            ),
            topLeft = Offset(frameLeft + w * 0.009f, frameTop + h * 0.008f),
            size = Size(panelWidth, frameHeight - h * 0.016f),
            cornerRadius = CornerRadius(8f, 8f)
        )
        drawCircle(
            color = Color(0xFFB98A4A),
            center = Offset(frameLeft + panelWidth - w * 0.014f, frameTop + frameHeight * 0.52f),
            radius = w * 0.0055f
        )

        if (doorStaffPresence > 0.01f) {
            val x = frameLeft + frameWidth * 0.56f
            val baseY = frameTop + frameHeight * 0.84f
            drawCircle(
                color = Color(0xCC1C2429).copy(alpha = 0.78f * doorStaffPresence),
                center = Offset(x, baseY - h * 0.24f),
                radius = w * 0.025f
            )
            drawRoundRect(
                color = Color(0xCC172027).copy(alpha = 0.72f * doorStaffPresence),
                topLeft = Offset(x - w * 0.03f, baseY - h * 0.205f),
                size = Size(w * 0.06f, h * 0.20f),
                cornerRadius = CornerRadius(18f, 18f)
            )
        }

        // Desk phone indicator in the lower-left foreground.
        val phoneX = w * 0.105f
        val phoneY = h * 0.805f
        drawRoundRect(
            color = Color(0xCC111518),
            topLeft = Offset(phoneX - w * 0.055f, phoneY - h * 0.025f),
            size = Size(w * 0.11f, h * 0.055f),
            cornerRadius = CornerRadius(16f, 16f)
        )
        drawRoundRect(
            color = Color(0xFF242B30),
            topLeft = Offset(phoneX - w * 0.042f, phoneY - h * 0.044f),
            size = Size(w * 0.084f, h * 0.022f),
            cornerRadius = CornerRadius(18f, 18f)
        )
        val indicatorAlpha = if (scene.phoneRinging) phonePulse else 0.22f
        drawCircle(
            color = Color(0xFFE34C45).copy(alpha = indicatorAlpha),
            center = Offset(phoneX + w * 0.034f, phoneY - h * 0.010f),
            radius = w * 0.006f * if (scene.phoneRinging) (0.90f + phonePulse * 0.18f) else 1f
        )

        // Warm practical lamp / monitor bloom. It remains quiet during listening.
        val practicalAlpha = practicalPulse * if (phase == CallPhase.LISTENING) 0.36f else 0.58f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x66E9B86D).copy(alpha = practicalAlpha), Color.Transparent),
                center = Offset(w * 0.73f, h * 0.29f),
                radius = w * 0.13f
            ),
            center = Offset(w * 0.73f, h * 0.29f),
            radius = w * 0.13f
        )

        // Extremely subtle foreground vignette for depth.
        val vignette = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = vignette,
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color(0x33000000)),
                center = Offset(w * 0.50f, h * 0.46f),
                radius = w * 0.80f
            )
        )
    }
}
