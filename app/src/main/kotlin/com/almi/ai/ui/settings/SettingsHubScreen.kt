package com.almi.ai.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.almi.ai.data.preferences.AiMode
import com.almi.ai.data.preferences.AppThemeMode

@Composable
fun SettingsHubScreen(
    viewModel: SettingsViewModel,
    language: String,
    onOpenAi: () -> Unit,
    onOpenBodyLab: () -> Unit,
    onOpenAvatar: () -> Unit,
) {
    val theme by viewModel.themeMode.collectAsState()
    val aiMode by viewModel.aiMode.collectAsState()
    val google by viewModel.googleAiStudioSettings.collectAsState()
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("ALMI / CONTROL", color = scheme.tertiary, style = MaterialTheme.typography.labelSmall)
            Text(
                if (language == "ar") "الإعدادات" else "Settings",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (language == "ar") "الجسم، الأفاتار، الذكاء والمظهر في مكان واحد." else "Body, avatar, AI and appearance in one place.",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Text(if (language == "ar") "ملفك" else "YOUR PROFILE", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Portal(
            icon = Icons.Outlined.Straighten,
            title = "Body Map",
            subtitle = if (language == "ar") "راجع قياساتك وعدّل أي نقطة مباشرة." else "Review measurements and edit any point directly.",
            meta = if (language == "ar") "قياسات" else "MEASUREMENTS",
            emphasized = true,
            onClick = onOpenBodyLab,
        )
        Portal(
            icon = Icons.Outlined.PersonOutline,
            title = if (language == "ar") "الأفاتار" else "Avatar",
            subtitle = if (language == "ar") "صورة أنمي ثابتة مرتبطة بهوية واحدة." else "A static anime portrait tied to one consistent identity.",
            meta = if (language == "ar") "صورة" else "IMAGE",
            emphasized = false,
            onClick = onOpenAvatar,
        )

        Text(if (language == "ar") "الذكاء" else "INTELLIGENCE", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Portal(
            icon = Icons.Outlined.AutoAwesome,
            title = if (language == "ar") "محرك الذكاء الاصطناعي" else "AI engine",
            subtitle = if (google.connected) {
                if (language == "ar") "Google AI Studio متصل • ويمكن إدارة بقية المزوّدات من هنا." else "Google AI Studio connected • manage other providers here too."
            } else {
                if (language == "ar") "المسار الحالي: ${engineName(aiMode, language)}" else "Current route: ${engineName(aiMode, language)}"
            },
            meta = if (google.connected) "GOOGLE + ${engineShort(aiMode)}" else engineShort(aiMode),
            emphasized = false,
            onClick = onOpenAi,
        )

        Text(if (language == "ar") "التطبيق" else "APP", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        ControlCard(icon = Icons.Outlined.Language, title = if (language == "ar") "اللغة" else "Language") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("العربية", language == "ar", { viewModel.setLanguage("ar") }, Modifier.weight(1f))
                Choice("English", language == "en", { viewModel.setLanguage("en") }, Modifier.weight(1f))
            }
        }
        ControlCard(icon = Icons.Outlined.Palette, title = if (language == "ar") "المظهر" else "Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ThemeChoice(AppThemeMode.SYSTEM, theme, if (language == "ar") "تلقائي" else "Auto", Icons.Outlined.PhoneAndroid, viewModel::setThemeMode, Modifier.weight(1f))
                ThemeChoice(AppThemeMode.LIGHT, theme, if (language == "ar") "فاتح" else "Light", Icons.Outlined.LightMode, viewModel::setThemeMode, Modifier.weight(1f))
                ThemeChoice(AppThemeMode.DARK, theme, if (language == "ar") "داكن" else "Dark", Icons.Outlined.DarkMode, viewModel::setThemeMode, Modifier.weight(1f))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = scheme.primary) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.padding(10.dp).size(20.dp), tint = scheme.onPrimary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (language == "ar") "بياناتك تحت سيطرتك" else "Your data stays under your control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (language == "ar") "القياسات والتفضيلات تحفظ محليًا. الاتصال الخارجي يحدث فقط للميزات التي تحتاج مزود AI." else "Measurements and preferences are stored locally. External requests only occur for features that require an AI provider.",
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Portal(icon: ImageVector, title: String, subtitle: String, meta: String, emphasized: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (emphasized) scheme.primary else scheme.surface
    val fg = if (emphasized) scheme.onPrimary else scheme.onSurface
    val muted = if (emphasized) scheme.onPrimary.copy(alpha = .62f) else scheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        color = bg,
        border = if (emphasized) null else BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = if (emphasized) 7.dp else 0.dp,
    ) {
        Row(modifier = Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Surface(shape = RoundedCornerShape(17.dp), color = if (emphasized) androidx.compose.ui.graphics.Color.White.copy(alpha = .10f) else scheme.surfaceVariant) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(11.dp).size(22.dp), tint = fg)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(meta, color = muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(subtitle, color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ControlCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = scheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = if (selected) scheme.primary else scheme.surfaceVariant, border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant)) {
        Text(label, modifier = Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center, color = if (selected) scheme.onPrimary else scheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ThemeChoice(mode: AppThemeMode, selected: AppThemeMode, label: String, icon: ImageVector, onClick: (AppThemeMode) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val active = mode == selected
    Surface(modifier = modifier.clickable { onClick(mode) }, shape = RoundedCornerShape(16.dp), color = if (active) scheme.primary else scheme.surfaceVariant, border = BorderStroke(1.dp, if (active) scheme.primary else scheme.outlineVariant)) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (active) scheme.onPrimary else scheme.onSurfaceVariant)
            Text(label, color = if (active) scheme.onPrimary else scheme.onSurface, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun engineName(mode: AiMode, language: String): String = when (mode) {
    AiMode.OPENROUTER -> "OpenRouter"
    AiMode.CUSTOM -> if (language == "ar") "واجهة مخصصة" else "Custom API"
    AiMode.FREE_AUTO -> if (language == "ar") "مجاني تلقائي" else "Free Auto"
}

private fun engineShort(mode: AiMode): String = when (mode) {
    AiMode.OPENROUTER -> "OPENROUTER"
    AiMode.CUSTOM -> "CUSTOM"
    AiMode.FREE_AUTO -> "FREE"
}
