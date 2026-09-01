package com.malik.alshurti.livev2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LiveCallScreen()
            }
        }
    }
}

@Composable
private fun LiveCallScreen(vm: LiveCallViewModel = viewModel()) {
    val context = LocalContext.current
    val ui = vm.ui.collectAsStateWithLifecycle().value
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.setMicrophonePermission(it) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.setMicrophonePermission(true)
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val officeDrawable = remember {
        context.resources.getIdentifier("cinematic_office_reference", "drawable", context.packageName)
    }
    val voiceScale by animateFloatAsState(
        targetValue = 1f + ui.outputLevel * 0.035f,
        label = "native-audio-presence"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF121A20), Color(0xFF071015), Color.Black)
                )
            )
    ) {
        if (officeDrawable != 0) {
            Image(
                painter = painterResource(officeDrawable),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(voiceScale),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 18.dp, end = 18.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = 0.68f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "الشرطي • LIVE V2",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = ui.status,
                    color = if (ui.error == null) Color(0xFFB9F6CA) else Color(0xFFFFCDD2),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AudioPresence(ui)
            Spacer(Modifier.height(10.dp))

            if (ui.userText.isNotBlank()) {
                Transcript("أنت", ui.userText, 0.78f)
            }
            if (ui.policeText.isNotBlank()) {
                Transcript("الشرطي", ui.policeText, 1f)
            }
            ui.error?.let {
                Text(
                    text = it,
                    color = Color(0xFFFFCDD2),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ui.callActive) {
                    Button(
                        onClick = vm::endCall,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E0000))
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = null)
                        Text(" إنهاء")
                    }
                } else {
                    Button(
                        onClick = {
                            if (ui.permissionGranted) vm.startCall()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null)
                        Text(" اتصال")
                    }
                }

                Button(
                    onClick = vm::retry,
                    enabled = ui.permissionGranted,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238))
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text(" إعادة")
                }
            }

            Spacer(Modifier.height(9.dp))
            Text(
                text = "2.0.0-live-alpha1 • Gemini 3.1 Flash Live • Native A2A",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AudioPresence(ui: LiveCallViewModel.UiState) {
    val level = maxOf(ui.inputLevel * 0.72f, ui.outputLevel)
    val pulse by animateFloatAsState(1f + level * 0.82f, label = "audio-pulse")
    val label = when (ui.sessionState) {
        GeminiLiveSession.State.CONNECTING -> "يتصل"
        GeminiLiveSession.State.LISTENING -> "مباشر"
        GeminiLiveSession.State.USER_SPEAKING -> "يسمع"
        GeminiLiveSession.State.POLICE_SPEAKING -> "يتكلم"
        GeminiLiveSession.State.ERROR -> "خطأ"
        GeminiLiveSession.State.CLOSED -> "مغلق"
        GeminiLiveSession.State.IDLE -> "جاهز"
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .scale(pulse)
                .alpha(0.24f + level * 0.62f)
                .background(
                    if (ui.error == null) Color(0xFF69F0AE) else Color(0xFFEF5350),
                    CircleShape
                )
        )
        Box(
            Modifier
                .size(52.dp)
                .background(Color.Black.copy(alpha = 0.78f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Transcript(who: String, text: String, alpha: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(who, color = Color.White.copy(alpha = 0.58f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            text = text,
            color = Color.White.copy(alpha = alpha),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Start
        )
    }
}
