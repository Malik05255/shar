package com.almi.ai.ui.v12

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almi.ai.data.preferences.AvatarAppearance
import com.almi.ai.data.preferences.AvatarPresentation
import com.almi.ai.data.preferences.BodyProfile
import com.almi.ai.data.preferences.JourneyMode

private enum class V12OnboardingStep { LANGUAGE, IDENTITY, AVATAR }

@Composable
internal fun V12OnboardingScreen(
    language: String,
    appearance: AvatarAppearance,
    bodyProfile: BodyProfile,
    digitalTwinSnapshotUri: String?,
    onLanguageChange: (String) -> Unit,
    onJourneyMode: (JourneyMode) -> Unit,
    onAvatarPresentation: (AvatarPresentation) -> Unit,
    onAvatarHair: (String) -> Unit,
    onAvatarHairColor: (String) -> Unit,
    onAvatarSkinColor: (String) -> Unit,
    onAvatarEyes: (String) -> Unit,
    onAvatarEyebrows: (String) -> Unit,
    onAvatarMouth: (String) -> Unit,
    onComplete: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(V12OnboardingStep.LANGUAGE) }

    when (step) {
        V12OnboardingStep.LANGUAGE -> LanguagePortal(
            language = language,
            onPick = {
                onLanguageChange(it)
                step = V12OnboardingStep.IDENTITY
            },
        )

        V12OnboardingStep.IDENTITY -> IdentityPortal(
            language = language,
            onAvatar = {
                onJourneyMode(JourneyMode.AVATAR)
                step = V12OnboardingStep.AVATAR
            },
            onPhoto = {
                onJourneyMode(JourneyMode.PHOTO)
                onComplete()
            },
            onBack = { step = V12OnboardingStep.LANGUAGE },
        )

        V12OnboardingStep.AVATAR -> V12AvatarScreen(
            language = language,
            appearance = appearance,
            bodyProfile = bodyProfile,
            digitalTwinSnapshotUri = digitalTwinSnapshotUri,
            onPresentation = onAvatarPresentation,
            onHair = onAvatarHair,
            onHairColor = onAvatarHairColor,
            onSkinColor = onAvatarSkinColor,
            onEyes = onAvatarEyes,
            onEyebrows = onAvatarEyebrows,
            onMouth = onAvatarMouth,
            onBack = { step = V12OnboardingStep.IDENTITY },
            onComplete = onComplete,
        )
    }
}

@Composable
private fun LanguagePortal(
    language: String,
    onPick: (String) -> Unit,
) {
    val p = V12Palettes.Index
    Box(Modifier.fillMaxSize().background(worldBrush(p)).statusBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
                Column {
                    Text("ALMI", color = p.ink, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp)
                    Text("BOOT / 12", color = p.muted, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                }
                Surface(shape = CircleShape, color = p.ink) {
                    Text("12", modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), color = p.signalInk, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (language == "ar") "اختر\nلغة النظام" else "CHOOSE\nSYSTEM LANGUAGE",
                    color = p.ink,
                    fontSize = 43.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp,
                )
                Text(
                    if (language == "ar") "هذه ليست صفحة إعدادات. هذا هو أول قرار في هويتك داخل ALMI." else "THIS IS NOT A SETTINGS PAGE. IT IS THE FIRST IDENTITY DECISION.",
                    color = p.muted,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = .55.sp,
                )
            }

            Row(Modifier.fillMaxWidth().height(214.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                LanguageDoor(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    code = "AR",
                    title = "العربية",
                    subtitle = "RTL / العربية",
                    active = language == "ar",
                    p = p,
                    onClick = { onPick("ar") },
                )
                LanguageDoor(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    code = "EN",
                    title = "English",
                    subtitle = "LTR / ENGLISH",
                    active = language != "ar",
                    p = p,
                    onClick = { onPick("en") },
                )
            }
        }
    }
}

@Composable
private fun LanguageDoor(
    modifier: Modifier,
    code: String,
    title: String,
    subtitle: String,
    active: Boolean,
    p: V12Palette,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 54.dp, bottomEnd = 14.dp, bottomStart = 42.dp),
        color = if (active) p.ink else p.panel,
        border = BorderStroke(1.dp, if (active) p.ink else p.edge),
        shadowElevation = if (active) 8.dp else 0.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(15.dp), Arrangement.SpaceBetween) {
            Text(code, color = if (active) p.signalInk else p.muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = if (active) p.signalInk else p.ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = if (active) p.signalInk.copy(alpha = .58f) else p.muted, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            }
        }
    }
}

@Composable
private fun IdentityPortal(
    language: String,
    onAvatar: () -> Unit,
    onPhoto: () -> Unit,
    onBack: () -> Unit,
) {
    val p = V12Palettes.Index
    Box(Modifier.fillMaxSize().background(worldBrush(p)).statusBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column {
                    Text("ALMI / IDENTITY", color = p.muted, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    Text(if (language == "ar") "كيف نمثلك؟" else "WHO IS YOU?", color = p.ink, fontSize = 31.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                }
                V12BackControl(p, if (language == "ar") "اللغة" else "LANGUAGE", onBack)
            }

            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                IdentityDoor(
                    number = "01",
                    title = if (language == "ar") "ابنِ شخصيتك" else "BUILD YOUR BODY",
                    subtitle = if (language == "ar") "شخصية ثلاثية الأبعاد، ثم Body Map والقياسات" else "3D IDENTITY → BODY MAP → FIT",
                    glyph = V12GlyphType.AVATAR,
                    palette = V12Palettes.Avatar,
                    modifier = Modifier.fillMaxWidth().height(286.dp),
                    onClick = onAvatar,
                )
                IdentityDoor(
                    number = "02",
                    title = if (language == "ar") "استخدم صورتك" else "USE YOUR PHOTO",
                    subtitle = if (language == "ar") "ادخل مباشرة إلى FIT والتقط أو اختر صورة" else "ENTER FIT AND CAPTURE / PICK A PHOTO",
                    glyph = V12GlyphType.CAMERA,
                    palette = V12Palettes.Fit,
                    modifier = Modifier.fillMaxWidth().height(168.dp),
                    onClick = onPhoto,
                )
            }

            Text(
                if (language == "ar") "يمكن تغيير هذا المسار لاحقًا. لن نحذف بيانات الجسم أو إعدادات الذكاء الاصطناعي." else "YOU CAN SWITCH LATER. BODY DATA AND AI ROUTES ARE PRESERVED.",
                color = p.muted,
                fontSize = 8.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .45.sp,
            )
        }
    }
}

@Composable
private fun IdentityDoor(
    number: String,
    title: String,
    subtitle: String,
    glyph: V12GlyphType,
    palette: V12Palette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 58.dp, bottomEnd = 18.dp, bottomStart = 46.dp),
        color = palette.panel,
        border = BorderStroke(1.dp, palette.edge),
        shadowElevation = 7.dp,
    ) {
        Box(Modifier.fillMaxSize().padding(17.dp)) {
            Text(number, modifier = Modifier.align(Alignment.TopStart), color = palette.signal, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Surface(modifier = Modifier.align(Alignment.TopEnd), shape = CircleShape, color = palette.signal.copy(alpha = .12f)) {
                V12Glyph(glyph, palette.signal, Modifier.padding(13.dp).size(32.dp))
            }
            Column(modifier = Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = palette.ink, fontSize = 27.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black, letterSpacing = (-.8).sp)
                Text(subtitle, color = palette.muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
            }
        }
    }
}
