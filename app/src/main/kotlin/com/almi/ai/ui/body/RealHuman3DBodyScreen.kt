package com.almi.ai.ui.body

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile

/**
 * Main-process gateway only. The actual measurement UI is a native Android Activity whose
 * SurfaceView is owned directly by Filament; Compose never owns the renderer surface.
 */
@Composable
fun RealHuman3DBodyScreen(
    language: String,
    profile: BodyProfile,
    onHeightChanged: (Float) -> Unit,
    onWeightChanged: (Float) -> Unit,
    onMeasurementChanged: (BodyMeasurePoint, Float) -> Unit,
    onMeasurementCleared: (BodyMeasurePoint) -> Unit,
    onSnapshotReady: (String) -> Unit = {},
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var launched by rememberSaveable { mutableStateOf(false) }
    var sessionActive by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun applyResult(updated: BodyProfile) {
        if (updated.hasExplicitHeight) onHeightChanged(updated.heightInches)
        if (updated.hasExplicitWeight) onWeightChanged(updated.weightPounds)
        BodyMeasurePoint.entries.forEach { point ->
            val before = profile.measurementsInches[point]
            val after = updated.measurementsInches[point]
            when {
                after != null && after != before -> onMeasurementChanged(point, after)
                after == null && before != null -> onMeasurementCleared(point)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        sessionActive = false
        if (result.resultCode == Activity.RESULT_OK) {
            applyResult(BodyMeasurementContract.readProfile(result.data))
            onComplete()
        } else {
            status = if (language == "ar") "أُغلقت جلسة القياسات. اضغط لإعادة فتح Filament." else "Measurement session closed. Tap to reopen Filament."
        }
    }

    fun openMeasurement() {
        if (sessionActive) return
        launched = true
        sessionActive = true
        status = null
        launcher.launch(BodyMeasurementContract.createIntent(context, language, profile))
    }

    LaunchedEffect(Unit) {
        if (!launched) openMeasurement()
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF04101E)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ALMI / FILAMENT", color = Color(0xFF86BCFF), fontWeight = FontWeight.Bold)
        Text(
            status ?: if (language == "ar") "يتم فتح شاشة القياسات…" else "Opening measurements…",
            modifier = Modifier.padding(vertical = 18.dp),
            color = Color(0xFF91A8C5),
            textAlign = TextAlign.Center,
        )
        if (!sessionActive) {
            Button(onClick = ::openMeasurement, modifier = Modifier.fillMaxWidth()) {
                Text(if (language == "ar") "فتح القياسات" else "Open measurements")
            }
        }
    }
}
