package com.almi.ai.ui.v12

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almi.ai.data.preferences.AiMode
import com.almi.ai.data.preferences.AppThemeMode
import com.almi.ai.ui.settings.SettingsViewModel

@Composable
internal fun V12ControlScreen(
    viewModel: SettingsViewModel,
    language: String,
    bodyReady: Boolean,
    avatarReady: Boolean,
    onBack: () -> Unit,
    onBody: () -> Unit,
    onAvatar: () -> Unit,
    onAi: () -> Unit,
) {
    val p = V12Palettes.Control
    val theme by viewModel.themeMode.collectAsState()
    val aiMode by viewModel.aiMode.collectAsState()
    val google by viewModel.googleAiStudioSettings.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(worldBrush(p)).statusBarsPadding().padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("ALMI / SYSTEM", color = p.signal, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                Text(if (language == "ar") "تحكم" else "CONTROL", color = p.ink, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-.8).sp)
            }
            V12BackControl(p, if (language == "ar") "العوالم" else "WORLDS", onBack)
        }

        SystemBand(
            number = "01",
            title = if (language == "ar") "الجسم" else "BODY",
            subtitle = if (bodyReady) (if (language == "ar") "ملف القياسات جاهز" else "MEASUREMENT PROFILE READY") else (if (language == "ar") "غير معاير" else "NOT CALIBRATED"),
            glyph = V12GlyphType.BODY,
            signal = V12Palettes.Body.signal,
            p = p,
            tall = true,
            onClick = onBody,
        )
        SystemBand(
            number = "02",
            title = if (language == "ar") "الهوية" else "IDENTITY",
            subtitle = if (avatarReady) "3D CHARACTER / READY" else "3D CHARACTER / BUILD",
            glyph = V12GlyphType.AVATAR,
            signal = V12Palettes.Avatar.signal,
            p = p,
            tall = false,
            onClick = onAvatar,
        )
        SystemBand(
            number = "03",
            title = if (language == "ar") "الذكاء" else "INTELLIGENCE",
            subtitle = if (google.active) "GOOGLE / ACTIVE" else when (aiMode) {
                AiMode.OPENROUTER -> "OPENROUTER / ACTIVE"
                AiMode.CUSTOM -> "CUSTOM / ACTIVE"
                AiMode.FREE_AUTO -> "FREE AUTO / ACTIVE"
            },
            glyph = V12GlyphType.AI,
            signal = V12Palettes.Ai.signal,
            p = p,
            tall = false,
            onClick = onAi,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 36.dp, bottomEnd = 12.dp, bottomStart = 36.dp),
            color = p.panel,
            border = BorderStroke(1.dp, p.edge),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V12Glyph(V12GlyphType.CONTROL, p.signal, Modifier.size(22.dp))
                    Column {
                        Text("04 / INTERFACE", color = p.signal, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                        Text(if (language == "ar") "الواجهة" else "INTERFACE", color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OptionPill("العربية", language == "ar", p, Modifier.weight(1f)) { viewModel.setLanguage("ar") }
                    OptionPill("English", language == "en", p, Modifier.weight(1f)) { viewModel.setLanguage("en") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OptionPill(if (language == "ar") "تلقائي" else "SYSTEM", theme == AppThemeMode.SYSTEM, p, Modifier.weight(1f)) { viewModel.setThemeMode(AppThemeMode.SYSTEM) }
                    OptionPill(if (language == "ar") "فاتح" else "LIGHT", theme == AppThemeMode.LIGHT, p, Modifier.weight(1f)) { viewModel.setThemeMode(AppThemeMode.LIGHT) }
                    OptionPill(if (language == "ar") "داكن" else "DARK", theme == AppThemeMode.DARK, p, Modifier.weight(1f)) { viewModel.setThemeMode(AppThemeMode.DARK) }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
            Text(
                "LOCAL FIRST\nAI ONLY WHEN YOU ASK",
                color = p.muted,
                fontSize = 8.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .8.sp,
            )
            Surface(shape = CircleShape, color = p.signal.copy(alpha = .12f)) {
                Text("12", Modifier.padding(12.dp), color = p.signal, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun SystemBand(
    number: String,
    title: String,
    subtitle: String,
    glyph: V12GlyphType,
    signal: Color,
    p: V12Palette,
    tall: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(if (tall) 128.dp else 92.dp).clickable(onClick = onClick),
        shape = if (tall) RoundedCornerShape(topStart = 42.dp, topEnd = 10.dp, bottomEnd = 42.dp, bottomStart = 10.dp) else RoundedCornerShape(topStart = 10.dp, topEnd = 28.dp, bottomEnd = 10.dp, bottomStart = 28.dp),
        color = p.panel,
        border = BorderStroke(1.dp, p.edge),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = if (tall) RoundedCornerShape(24.dp) else CircleShape, color = signal.copy(alpha = .13f)) {
                V12Glyph(glyph, signal, Modifier.padding(if (tall) 15.dp else 12.dp).size(if (tall) 38.dp else 27.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$number /", color = signal, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                Text(title, color = p.ink, fontSize = if (tall) 23.sp else 18.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = p.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
            }
            Text("↗", color = p.ink.copy(alpha = .56f), fontSize = 23.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun OptionPill(text: String, active: Boolean, p: V12Palette, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(42.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (active) p.signal else p.background,
        border = BorderStroke(1.dp, if (active) p.signal else p.edge),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (active) p.signalInk else p.ink, fontSize = 8.5.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }
    }
}
