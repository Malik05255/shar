package com.almi.ai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almi.ai.ui.theme.LocalAlmiUiScale

@Composable
fun DimensionCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scale = LocalAlmiUiScale.current
    val click = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Surface(
        modifier = modifier.then(click),
        shape = RoundedCornerShape(((if (emphasized) 25f else 20f) * scale).dp),
        color = if (emphasized) scheme.primary else scheme.surface,
        border = if (emphasized) null else BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = if (emphasized) (6f * scale).dp else 0.dp,
        content = content,
    )
}

@Composable
fun Glossy3DIcon(icon: ImageVector, modifier: Modifier = Modifier, active: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val scale = LocalAlmiUiScale.current
    val boxSize = (44f * scale).dp
    Surface(
        modifier = modifier.size(boxSize),
        shape = RoundedCornerShape((15f * scale).dp),
        color = if (active) scheme.tertiary else scheme.surfaceVariant,
        border = BorderStroke(1.dp, if (active) scheme.tertiary else scheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size((20f * scale).dp),
                tint = if (active) scheme.onTertiary else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AiOrb3D(modifier: Modifier = Modifier, label: String = "AI") {
    val scheme = MaterialTheme.colorScheme
    val scale = LocalAlmiUiScale.current
    val diameter = (116f * scale).dp
    val motion = rememberInfiniteTransition(label = "ai-orb-v8")
    val pulse by motion.animateFloat(
        initialValue = .96f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1_800), RepeatMode.Reverse),
        label = "ai-orb-pulse",
    )
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val halo = size.minDimension * .47f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(scheme.tertiary.copy(alpha = .20f), Color.Transparent),
                    center = center,
                    radius = halo,
                ),
                radius = halo,
                center = center,
            )
            drawCircle(scheme.primary, size.minDimension * .29f, center)
            drawCircle(scheme.outlineVariant.copy(alpha = .8f), size.minDimension * .39f, center, style = Stroke(1.2f))
            drawArc(
                color = scheme.tertiary,
                startAngle = -35f,
                sweepAngle = 94f,
                useCenter = false,
                topLeft = Offset(center.x - size.minDimension * .39f, center.y - size.minDimension * .39f),
                size = androidx.compose.ui.geometry.Size(size.minDimension * .78f, size.minDimension * .78f),
                style = Stroke(3.0f, cap = StrokeCap.Round),
            )
            drawCircle(Color(0xFF66E7B0), 4.3f, Offset(center.x + size.minDimension * .38f, center.y))
        }
        Text(label, color = scheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ConnectionPill(text: String, connected: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val scale = LocalAlmiUiScale.current
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = (9f * scale).dp, vertical = (6f * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((6f * scale).dp),
        ) {
            Surface(shape = CircleShape, color = if (connected) Color(0xFF42C98D) else scheme.outline) {
                Box(Modifier.size((7f * scale).dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
