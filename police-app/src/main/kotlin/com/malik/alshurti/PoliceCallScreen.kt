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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Pure cinematic observation surface.
 *
 * No written UI and no synthetic/game-like office overlay. Every visible movement must come from
 * the cinematic source itself or the real runtime 3D scene. Android permission/installer surfaces
 * remain system-owned and may contain text outside this composable.
 */
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

    // Facial performance is high-frequency state, independent from office scenario choreography.
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
    }
}

private const val CINEMATIC_ASPECT = 16f / 9f
