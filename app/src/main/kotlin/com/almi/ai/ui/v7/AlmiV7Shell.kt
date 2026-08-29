package com.almi.ai.ui.v7

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almi.ai.ui.theme.LocalAlmiUiScale

enum class AlmiV7Destination { STUDIO, AI, SETTINGS }

@Composable
fun AlmiV7Backdrop(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()
    }
}

/**
 * Compact adaptive navigation dock. It keeps a stable visual footprint on small phones and stops
 * growing after tablet / large-phone widths, so navigation never becomes the largest object on the
 * screen.
 */
@Composable
fun AlmiV7BottomDock(
    selected: AlmiV7Destination,
    language: String,
    onStudio: () -> Unit,
    onAi: () -> Unit,
    onSettings: () -> Unit,
) {
    val scale = LocalAlmiUiScale.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = (12f * scale).dp, vertical = (8f * scale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape((22f * scale).dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = (8f * scale).dp,
        ) {
            Row(
                modifier = Modifier.padding((5f * scale).dp),
                horizontalArrangement = Arrangement.spacedBy((4f * scale).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockItem(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Checkroom,
                    label = if (language == "ar") "الاستوديو" else "Studio",
                    selected = selected == AlmiV7Destination.STUDIO,
                    onClick = onStudio,
                )
                DockItem(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.AutoAwesome,
                    label = if (language == "ar") "الذكاء" else "AI",
                    selected = selected == AlmiV7Destination.AI,
                    onClick = onAi,
                )
                DockItem(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Settings,
                    label = if (language == "ar") "الإعدادات" else "Settings",
                    selected = selected == AlmiV7Destination.SETTINGS,
                    onClick = onSettings,
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scale = LocalAlmiUiScale.current
    val background = if (selected) scheme.primary else Color.Transparent
    val foreground = if (selected) scheme.onPrimary else scheme.onSurfaceVariant

    Row(
        modifier = modifier
            .background(background, RoundedCornerShape((17f * scale).dp))
            .clickable(onClick = onClick)
            .padding(horizontal = (8f * scale).dp, vertical = (9f * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size((18f * scale).dp),
            tint = foreground,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = (6f * scale).dp),
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
