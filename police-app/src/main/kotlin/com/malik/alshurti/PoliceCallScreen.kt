package com.malik.alshurti

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current

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

    LaunchedEffect(state.viseme) {
        RuntimeOfficePlanBus.publishViseme(state.viseme)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07111C), Color(0xFF0B1823), Color(0xFF07111C))
                )
            )
    ) {
        val interactionSource = remember { MutableInteractionSource() }

        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = state.phase == CallPhase.SPEAKING || state.phase == CallPhase.ERROR,
                    onClick = {
                        if (state.phase == CallPhase.SPEAKING) viewModel.interruptAndListen()
                        else viewModel.retryListening()
                    }
                )
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewportAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else CINEMATIC_ASPECT
                val cinematicModifier = if (viewportAspect < CINEMATIC_ASPECT) {
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.96f)
                        .aspectRatio(CINEMATIC_ASPECT)
                } else {
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight(0.90f)
                        .aspectRatio(CINEMATIC_ASPECT)
                }

                RealPoliceDogStage(
                    mood = state.mood,
                    phase = state.phase,
                    viseme = state.viseme,
                    officeScene = state.officeScene,
                    modifier = cinematicModifier
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(28.dp))
                .padding(4.dp)
        ) {
            ModeButton(
                label = "إنترنت",
                selected = state.mode == VoiceMode.ONLINE,
                onClick = { viewModel.chooseMode(VoiceMode.ONLINE) }
            )
            ModeButton(
                label = "بدون إنترنت",
                selected = state.mode == VoiceMode.OFFLINE,
                onClick = { viewModel.chooseMode(VoiceMode.OFFLINE) }
            )
        }

        DeviceStatusStrip(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun DeviceStatusStrip(state: PoliceUiState, modifier: Modifier = Modifier) {
    val error = state.errorMessage?.takeIf { it.isNotBlank() }
    val detail = when {
        error != null -> error
        state.phase == CallPhase.LISTENING -> "تكلم الآن، الميكروفون يستمع"
        state.phase == CallPhase.THINKING -> "جاري فهم كلامك"
        state.phase == CallPhase.SPEAKING -> "الشرطي يتكلم"
        state.phase == CallPhase.STARTING -> "جاري بدء المحادثة"
        else -> "اضغط على الشاشة للمحاولة مرة أخرى"
    }
    val modeText = if (state.mode == VoiceMode.ONLINE) "ONLINE / GEMINI" else "OFFLINE / LOCAL"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "الشرطي v${BuildConfig.VERSION_NAME} • $modeText",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            color = if (error != null) Color(0xFFFFC6C6) else Color.White,
            fontWeight = if (error != null) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        if (state.heardText.isNotBlank() && state.phase != CallPhase.STARTING) {
            Text(
                text = "سمعت: ${state.heardText.take(90)}",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) Color(0xFFE9F3F8) else Color.Transparent,
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF102630) else Color.White.copy(alpha = 0.88f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

private const val CINEMATIC_ASPECT = 16f / 9f
