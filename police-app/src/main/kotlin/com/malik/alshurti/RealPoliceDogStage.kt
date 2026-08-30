package com.malik.alshurti

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Production visual stage.
 *
 * The final character is a cinematic stylized-realistic 3D animated police dog:
 * believable canine anatomy/materials with expressive feature-film facial animation.
 * It is NOT a photographed real dog and NOT the old flat/cartoon mascot.
 *
 * Required body clips: Idle, Listen, Think, Smile, Serious.
 * Required talking/viseme clips: TalkOpen, TalkWide, TalkRound, TalkClosed, TalkRest.
 */
@Composable
fun RealPoliceDogStage(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasRealModel = remember {
        runCatching {
            context.assets.list(MODEL_DIRECTORY)
                ?.any { it.equals(MODEL_FILE, ignoreCase = true) } == true
        }.getOrDefault(false)
    }

    if (!hasRealModel) {
        VoicePreviewStage(modifier)
        return
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(
        modelLoader = modelLoader,
        fileLocation = MODEL_ASSET_PATH
    )

    SceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = null,
        autoCenterContent = true,
        autoFitContent = true,
        framingPadding = 0.08f
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                autoAnimate = false,
                animationName = animationFor(mood, phase, viseme),
                animationLoop = true,
                animationSpeed = animationSpeedFor(phase),
                scaleToUnits = 1.72f,
                centerOrigin = Position(x = 0f, y = -1f, z = 0f),
                position = Position(x = 0f, y = -0.18f, z = 0f)
            )
        }
    }
}

/**
 * Deliberately neutral: this is not a fake/cartoon replacement for the final dog.
 * It keeps the voice/conversation build testable while the licensed rigged GLB is
 * authored separately.
 */
@Composable
private fun VoicePreviewStage(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07111C)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(112.dp),
                shape = CircleShape,
                color = Color(0xFF102B3A),
                contentColor = Color.White
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "صوت",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "معاينة صوتية",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "المجسم السينمائي النهائي يُركّب بشكل مستقل",
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun animationFor(mood: DogMood, phase: CallPhase, viseme: MouthViseme): String = when {
    phase == CallPhase.SPEAKING -> when (viseme) {
        MouthViseme.OPEN -> "TalkOpen"
        MouthViseme.WIDE -> "TalkWide"
        MouthViseme.ROUND -> "TalkRound"
        MouthViseme.CLOSED -> "TalkClosed"
        MouthViseme.REST -> "TalkRest"
    }
    phase == CallPhase.LISTENING -> "Listen"
    phase == CallPhase.THINKING -> "Think"
    mood == DogMood.SMILE -> "Smile"
    mood == DogMood.SERIOUS || phase == CallPhase.ERROR -> "Serious"
    else -> "Idle"
}

private fun animationSpeedFor(phase: CallPhase): Float = when (phase) {
    CallPhase.SPEAKING -> 1.05f
    CallPhase.LISTENING -> 0.92f
    CallPhase.THINKING -> 0.82f
    else -> 0.75f
}

private const val MODEL_DIRECTORY = "models"
private const val MODEL_FILE = "police_dog.glb"
private const val MODEL_ASSET_PATH = "$MODEL_DIRECTORY/$MODEL_FILE"
