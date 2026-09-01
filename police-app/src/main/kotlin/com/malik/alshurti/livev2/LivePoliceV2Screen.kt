package com.malik.alshurti.livev2

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.malik.alshurti.BuildConfig
import com.malik.alshurti.R

/**
 * Voice-first V2 surface.
 *
 * The old cinematic/3D renderer is intentionally not mounted here. This screen is a clean device
 * acceptance surface for the new audio-to-audio architecture. Once Live audio is accepted on a real
 * phone, the animal portrait renderer plugs into this surface without touching the voice session.
 */
@Composable
fun LivePoliceV2Screen(viewModel: LivePoliceV2ViewModel = viewModel()) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onMicrophonePermission(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onMicrophonePermission(true)
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val speakingScale by animateFloatAsState(
        targetValue = 1f + state.outputLevel * 0.045f,
        label = "live-audio-scale"
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painter = painterResource(R.drawable.cinematic_office_reference),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(speakingScale),
            contentScale = ContentScale.Crop
        )

        // The V2 visual is intentionally restrained until the animal portrait pack is generated.
        // Audio energy still gives immediate proof that native PCM is being produced on-device.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 18.dp, end = 18.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.62f)
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "الشرطي • LIVE V2",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = state.status,
                    color = if (state.error == null) Color(0xFFB9F6CA) else Color(0xFFFFCDD2),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LivePulse(
                inputLevel = state.inputLevel,
                outputLevel = state.outputLevel,
                state = state.state
            )

            Spacer(Modifier.height(12.dp))

            if (state.userText.isNotBlank()) {
                TranscriptLine(label = "أنت", text = state.userText, alpha = 0.78f)
            }
            if (state.policeText.isNotBlank()) {
                TranscriptLine(label = "الشرطي", text = state.policeText, alpha = 1f)
            }
            state.error?.let {
                Text(
                    text = it,
                    color = Color(0xFFFFCDD2),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!state.started) {
                    Button(
                        onClick = {
                            if (state.microphoneGranted) viewModel.start()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null)
                        Text(" اتصال")
                    }
                } else {
                    Button(
                        onClick = viewModel::hangUp,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E0000))
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = null)
                        Text(" إنهاء")
                    }
                }

                Button(
                    onClick = viewModel::retry,
                    enabled = state.microphoneGranted,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238))
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text(" إعادة")
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME} • gemini-3.1-flash-live-preview • PCM 16k→24k",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LivePulse(
    inputLevel: Float,
    outputLevel: Float,
    state: GeminiLiveAudioEngine.State
) {
    val level = maxOf(inputLevel * 0.7f, outputLevel)
    val scale by animateFloatAsState(targetValue = 1f + level * 0.75f, label = "pulse")
    val label = when (state) {
        GeminiLiveAudioEngine.State.CONNECTING -> "يتصل"
        GeminiLiveAudioEngine.State.MODEL_SPEAKING -> "يتكلم"
        GeminiLiveAudioEngine.State.USER_SPEAKING -> "يسمع"
        GeminiLiveAudioEngine.State.LISTENING, GeminiLiveAudioEngine.State.READY -> "مباشر"
        GeminiLiveAudioEngine.State.ERROR -> "خطأ"
        GeminiLiveAudioEngine.State.CLOSED -> "مغلق"
        GeminiLiveAudioEngine.State.IDLE -> "جاهز"
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .scale(scale)
                .alpha(0.28f + level * 0.55f)
                .background(
                    color = if (state == GeminiLiveAudioEngine.State.ERROR) Color(0xFFEF5350) else Color(0xFF69F0AE),
                    shape = CircleShape
                )
        )
        Box(
            Modifier
                .size(52.dp)
                .background(Color.Black.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TranscriptLine(label: String, text: String, alpha: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            text = text,
            color = Color.White.copy(alpha = alpha),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Start
        )
    }
}
