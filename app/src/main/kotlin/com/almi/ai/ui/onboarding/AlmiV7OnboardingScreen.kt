package com.almi.ai.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.almi.ai.data.preferences.AvatarAppearance
import com.almi.ai.data.preferences.AvatarPresentation
import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import com.almi.ai.data.preferences.JourneyMode
import com.almi.ai.ui.avatar.AvatarDesignerScreen
import com.almi.ai.ui.body.RealHuman3DBodyScreen

private enum class IntroStage { LANGUAGE, JOURNEY, BODY, AVATAR, PHOTO }

@Composable
fun AlmiV7OnboardingScreen(
    language: String,
    profile: BodyProfile,
    avatarAppearance: AvatarAppearance,
    digitalTwinSnapshotUri: String?,
    onLanguageChange: (String) -> Unit,
    onJourneyMode: (JourneyMode) -> Unit,
    onHeightChanged: (Float) -> Unit,
    onWeightChanged: (Float) -> Unit,
    onMeasurementChanged: (BodyMeasurePoint, Float) -> Unit,
    onMeasurementCleared: (BodyMeasurePoint) -> Unit,
    onDigitalTwinSnapshot: (String) -> Unit,
    onAvatarPresentation: (AvatarPresentation) -> Unit,
    onAvatarHair: (String) -> Unit,
    onAvatarHairColor: (String) -> Unit,
    onAvatarSkinColor: (String) -> Unit,
    onAvatarAccessories: (String) -> Unit,
    onAvatarFacialHair: (String) -> Unit,
    onAvatarEyes: (String) -> Unit,
    onAvatarEyebrows: (String) -> Unit,
    onAvatarMouth: (String) -> Unit,
    onAvatarRandomize: () -> Unit,
    onComplete: () -> Unit,
) {
    var stageName by rememberSaveable { mutableStateOf(IntroStage.LANGUAGE.name) }
    val stage = runCatching { IntroStage.valueOf(stageName) }.getOrDefault(IntroStage.LANGUAGE)

    BackHandler(enabled = stage != IntroStage.LANGUAGE) {
        stageName = when (stage) {
            IntroStage.LANGUAGE -> IntroStage.LANGUAGE.name
            IntroStage.JOURNEY -> IntroStage.LANGUAGE.name
            IntroStage.BODY, IntroStage.PHOTO -> IntroStage.JOURNEY.name
            IntroStage.AVATAR -> IntroStage.BODY.name
        }
    }

    Crossfade(targetState = stage, animationSpec = tween(170), label = "almi-v8-intro") { current ->
        when (current) {
            IntroStage.LANGUAGE -> LanguageScreen(
                onArabic = {
                    onLanguageChange("ar")
                    stageName = IntroStage.JOURNEY.name
                },
                onEnglish = {
                    onLanguageChange("en")
                    stageName = IntroStage.JOURNEY.name
                },
            )
            IntroStage.JOURNEY -> JourneyScreen(
                language = language,
                onTwin = {
                    onJourneyMode(JourneyMode.AVATAR)
                    stageName = IntroStage.BODY.name
                },
                onPhoto = {
                    onJourneyMode(JourneyMode.PHOTO)
                    stageName = IntroStage.PHOTO.name
                },
            )
            IntroStage.BODY -> RealHuman3DBodyScreen(
                language = language,
                profile = profile,
                onHeightChanged = onHeightChanged,
                onWeightChanged = onWeightChanged,
                onMeasurementChanged = onMeasurementChanged,
                onMeasurementCleared = onMeasurementCleared,
                onSnapshotReady = onDigitalTwinSnapshot,
                onComplete = { stageName = IntroStage.AVATAR.name },
            )
            IntroStage.AVATAR -> AvatarDesignerScreen(
                language = language,
                appearance = avatarAppearance,
                bodyProfile = profile,
                digitalTwinSnapshotUri = digitalTwinSnapshotUri,
                onPresentation = onAvatarPresentation,
                onHair = onAvatarHair,
                onHairColor = onAvatarHairColor,
                onSkinColor = onAvatarSkinColor,
                onAccessories = onAvatarAccessories,
                onFacialHair = onAvatarFacialHair,
                onEyes = onAvatarEyes,
                onEyebrows = onAvatarEyebrows,
                onMouth = onAvatarMouth,
                onRandomize = onAvatarRandomize,
                onComplete = onComplete,
            )
            IntroStage.PHOTO -> PhotoScreen(language = language, onComplete = onComplete)
        }
    }
}

@Composable
private fun LanguageScreen(onArabic: () -> Unit, onEnglish: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.primary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(22.dp),
    ) {
        Surface(
            modifier = Modifier.align(Alignment.TopStart),
            shape = RoundedCornerShape(999.dp),
            color = Color.White.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Text(
                "ALMI  /  PRIVATE FIT LAB",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "ALMI",
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Fit before checkout.",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "A private body profile, a visual avatar, and AI try-on in one clean flow.",
                color = Color.White.copy(alpha = 0.52f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "اختر لغتك  ·  Choose your language",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelMedium,
            )
            Button(
                onClick = onArabic,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = scheme.primary,
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text("العربية", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onEnglish,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text("English", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun JourneyScreen(language: String, onTwin: () -> Unit, onPhoto: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("ALMI / SETUP", color = scheme.tertiary, style = MaterialTheme.typography.labelSmall)
        Text(
            tr(language, "ابدأ بالطريقة المناسبة لك", "Start your way"),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            tr(
                language,
                "المساران يصلان لنفس الاستوديو. التوأم الرقمي يعطي المقاس سياقًا أدق، والصورة هي الطريق الأسرع.",
                "Both paths reach the same studio. A digital twin adds fit context; a photo is the fastest route.",
            ),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(2.dp))
        JourneyCard(
            icon = Icons.Outlined.Person,
            number = "01",
            title = tr(language, "ابنِ توأمي الرقمي", "Build my digital twin"),
            description = tr(language, "قياسات تفاعلية على جسم 360° ثم أفاتار أنمي ثابت.", "Interactive 360° body measurements, then a static anime avatar."),
            emphasized = true,
            onClick = onTwin,
        )
        JourneyCard(
            icon = Icons.Outlined.PhotoCamera,
            number = "02",
            title = tr(language, "ابدأ بصورة", "Start with a photo"),
            description = tr(language, "تجاوز القياسات الآن وأضف صورة كاملة للجسم داخل الاستوديو.", "Skip measurements for now and add a full-body photo in Studio."),
            emphasized = false,
            onClick = onPhoto,
        )
        Spacer(Modifier.weight(1f))
        Text(
            tr(language, "يمكن تغيير هذه التفاصيل لاحقًا من الإعدادات.", "You can change these details later in Settings."),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun JourneyCard(
    icon: ImageVector,
    number: String,
    title: String,
    description: String,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (emphasized) scheme.primary else scheme.surface
    val fg = if (emphasized) scheme.onPrimary else scheme.onSurface
    val muted = if (emphasized) scheme.onPrimary.copy(alpha = 0.62f) else scheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        color = bg,
        border = if (emphasized) null else BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = if (emphasized) 8.dp else 0.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (emphasized) Color.White.copy(alpha = 0.10f) else scheme.surfaceVariant,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(11.dp).size(22.dp), tint = fg)
                }
                Text(number, color = muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(title, color = fg, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(description, color = muted, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(trFromTitle(title), color = fg, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(17.dp), tint = fg)
            }
        }
    }
}

@Composable
private fun PhotoScreen(language: String, onComplete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("ALMI / PHOTO", color = scheme.tertiary, style = MaterialTheme.typography.labelSmall)
        Text(
            tr(language, "صورة واحدة، بدون تعقيد", "One photo. No setup friction."),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            tr(language, "أضف الصورة داخل الاستوديو. الأفضل أن يظهر الجسم كاملًا بإضاءة متساوية ووضعية طبيعية.", "Add the photo in Studio. A full-body frame with even light and a natural stance works best."),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                PhotoRule("01", tr(language, "من الرأس إلى القدم", "Head to toe"))
                PhotoRule("02", tr(language, "خلفية هادئة", "Simple background"))
                PhotoRule("03", tr(language, "بدون فلاتر أو عدسة واسعة", "No filters or wide-angle lens"))
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(20.dp)) {
            Text(tr(language, "فتح الاستوديو", "Open Studio"), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PhotoRule(code: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(code, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

private fun tr(language: String, ar: String, en: String): String = if (language == "ar") ar else en
private fun trFromTitle(title: String): String = if (title.any { it in '\u0600'..'\u06FF' }) "متابعة" else "Continue"
