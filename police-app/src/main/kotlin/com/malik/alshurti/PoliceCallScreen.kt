package com.malik.alshurti

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PoliceCallScreen(viewModel: PoliceCallViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onMicrophonePermissionResult
    )

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onMicrophonePermissionResult(true)
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07111C))
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = state.phase == CallPhase.SPEAKING,
                    onClick = viewModel::interruptAndListen
                )
        ) {
            RealPoliceDogStage(
                mood = state.mood,
                phase = state.phase,
                viseme = state.viseme,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.30f),
                contentColor = Color.White
            ) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "طريقة التشغيل")
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(Color(0xFF10232E))
            ) {
                DropdownMenuItem(
                    text = { ModeMenuText("الإنترنت", state.mode == VoiceMode.ONLINE) },
                    onClick = {
                        menuExpanded = false
                        viewModel.chooseMode(VoiceMode.ONLINE)
                    }
                )
                DropdownMenuItem(
                    text = { ModeMenuText("بدون إنترنت", state.mode == VoiceMode.OFFLINE) },
                    onClick = {
                        menuExpanded = false
                        viewModel.chooseMode(VoiceMode.OFFLINE)
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0xCC0B1820),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    PhaseDot(state.phase)
                    Text(
                        text = state.statusText,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    if (state.phase == CallPhase.ERROR) {
                        IconButton(onClick = viewModel::startConversation, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "إعادة المحاولة", tint = Color.White)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.phase == CallPhase.SPEAKING,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = "المس الشاشة إذا أردت مقاطعة الشرطي",
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeMenuText(title: String, selected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFF75D4A8) else Color(0xFF637783))
        )
        Text(
            title,
            color = Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun PhaseDot(phase: CallPhase) {
    val colors = when (phase) {
        CallPhase.STARTING -> listOf(Color(0xFFF2C56B), Color(0xFFE19D3F))
        CallPhase.LISTENING -> listOf(Color(0xFF68E0B0), Color(0xFF3AAF83))
        CallPhase.THINKING -> listOf(Color(0xFF77BDE9), Color(0xFF3E83B5))
        CallPhase.SPEAKING -> listOf(Color(0xFF8FD5F4), Color(0xFF4C9BC8))
        CallPhase.ERROR -> listOf(Color(0xFFE98B7F), Color(0xFFB84C42))
    }
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(colors))
    )
}
