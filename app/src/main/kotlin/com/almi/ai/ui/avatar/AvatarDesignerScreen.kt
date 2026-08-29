package com.almi.ai.ui.avatar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.almi.ai.data.preferences.AvatarAppearance
import com.almi.ai.data.preferences.AvatarPresentation
import com.almi.ai.data.preferences.BodyProfile
import kotlin.math.roundToInt

/**
 * v8 avatar workshop. It is intentionally image-only: no GLB, no native renderer, no network.
 * Every thumbnail is the same identity with one controlled feature changed.
 */
@Composable
fun AvatarDesignerScreen(
    language: String,
    appearance: AvatarAppearance,
    bodyProfile: BodyProfile,
    digitalTwinSnapshotUri: String?,
    onPresentation: (AvatarPresentation) -> Unit,
    onHair: (String) -> Unit,
    onHairColor: (String) -> Unit,
    onSkinColor: (String) -> Unit,
    onAccessories: (String) -> Unit,
    onFacialHair: (String) -> Unit,
    onEyes: (String) -> Unit,
    onEyebrows: (String) -> Unit,
    onMouth: (String) -> Unit,
    onRandomize: () -> Unit,
    onComplete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("ALMI / AVATAR", color = scheme.tertiary, style = MaterialTheme.typography.labelSmall)
                Text(
                    tr(language, "صورتك الرقمية", "Your digital face"),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Surface(shape = RoundedCornerShape(999.dp), color = scheme.tertiaryContainer) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = scheme.tertiary)
                    Text(tr(language, "مرتبط بالجسم", "BODY LINKED"), style = MaterialTheme.typography.labelSmall, color = scheme.tertiary)
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(34.dp),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outlineVariant),
            shadowElevation = 5.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.86f)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(27.dp)),
            ) {
                AnimeAvatarPortrait(appearance = appearance, modifier = Modifier.fillMaxSize())
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = scheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Text(
                        tr(language, "تتغير المعاينة فورًا", "LIVE IMAGE PREVIEW"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        ChoiceSection(title = tr(language, "الشخصية", "Character")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segment(
                    label = tr(language, "أنثى", "Female"),
                    selected = appearance.presentation == AvatarPresentation.FEMININE,
                    onClick = { onPresentation(AvatarPresentation.FEMININE) },
                    modifier = Modifier.weight(1f),
                )
                Segment(
                    label = tr(language, "ذكر", "Male"),
                    selected = appearance.presentation == AvatarPresentation.MASCULINE,
                    onClick = { onPresentation(AvatarPresentation.MASCULINE) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ChoiceSection(title = tr(language, "الشعر", "Hair")) {
            val hair = if (appearance.presentation == AvatarPresentation.FEMININE) {
                listOf(
                    Option("bob", tr(language, "بوب", "Bob")),
                    Option("shortCurly", tr(language, "كيرلي قصير", "Short curly")),
                    Option("longButNotTooLong", tr(language, "طويل", "Long")),
                    Option("shortFlat", tr(language, "قصير", "Short")),
                )
            } else {
                listOf(
                    Option("shortFlat", tr(language, "قصير", "Short")),
                    Option("shortCurly", tr(language, "كيرلي", "Curly")),
                    Option("bob", tr(language, "متوسط", "Medium")),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                hair.forEach { option ->
                    PortraitOption(
                        label = option.label,
                        preview = appearance.copy(hairVariant = option.value),
                        selected = appearance.hairVariant == option.value,
                        onClick = { onHair(option.value) },
                    )
                }
            }
        }

        ChoiceSection(title = tr(language, "لون الشعر", "Hair colour")) {
            Swatches(
                values = listOf("241A19", "5D382C", "A45C32", "D8B06A"),
                current = appearance.hairColor,
                onSelect = onHairColor,
            )
        }

        ChoiceSection(title = tr(language, "البشرة", "Skin tone")) {
            Swatches(
                values = listOf("F6D5C1", "E7B58E", "C9855B", "855134"),
                current = appearance.skinColor,
                onSelect = onSkinColor,
            )
        }

        ChoiceSection(title = tr(language, "النظارات", "Glasses")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Option("none", tr(language, "بدون", "None")),
                    Option("round", tr(language, "دائرية", "Round")),
                    Option("wayfarers", tr(language, "مربعة", "Square")),
                ).forEach { option ->
                    Segment(
                        label = option.label,
                        selected = appearance.accessoriesVariant == option.value,
                        onClick = { onAccessories(option.value) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (appearance.presentation == AvatarPresentation.MASCULINE) {
            ChoiceSection(title = tr(language, "اللحية", "Facial hair")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Option("none", tr(language, "بدون", "None")),
                        Option("beardLight", tr(language, "خفيفة", "Light")),
                        Option("moustacheFancy", tr(language, "شارب", "Moustache")),
                    ).forEach { option ->
                        Segment(
                            label = option.label,
                            selected = appearance.facialHairVariant == option.value,
                            onClick = { onFacialHair(option.value) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        BodyLinkCard(language = language, profile = bodyProfile)

        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(tr(language, "حفظ الأفاتار", "Save avatar"), fontWeight = FontWeight.SemiBold)
        }
        Text(
            tr(language, "خيارات قليلة متعمدة حتى تبقى الشخصية متناسقة.", "A deliberately small option set keeps the identity consistent."),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
    }
}

private data class Option(val value: String, val label: String)

@Composable
private fun ChoiceSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) scheme.primary else scheme.surface,
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 13.dp),
            textAlign = TextAlign.Center,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PortraitOption(label: String, preview: AvatarAppearance, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.width(112.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Surface(
            modifier = Modifier.size(112.dp),
            shape = RoundedCornerShape(24.dp),
            color = scheme.surface,
            border = BorderStroke(2.dp, if (selected) scheme.tertiary else scheme.outlineVariant),
        ) {
            AnimeAvatarPortrait(appearance = preview, modifier = Modifier.fillMaxSize())
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) scheme.tertiary else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Swatches(values: List<String>, current: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        values.forEach { value ->
            val selected = current.equals(value, ignoreCase = true)
            Surface(
                modifier = Modifier.size(52.dp).clickable { onSelect(value) },
                shape = CircleShape,
                color = hex(value),
                border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant),
            ) {}
        }
    }
}

@Composable
private fun BodyLinkCard(language: String, profile: BodyProfile) {
    val scheme = MaterialTheme.colorScheme
    val facts = buildList {
        if (profile.hasExplicitHeight) add("${profile.heightCentimeters.roundToInt()} cm")
        if (profile.hasExplicitWeight) add("${profile.weightKilograms.roundToInt()} kg")
        profile.measurementsInches[com.almi.ai.data.preferences.BodyMeasurePoint.CHEST]?.let { add("${(it * 2.54f).roundToInt()} cm") }
        profile.measurementsInches[com.almi.ai.data.preferences.BodyMeasurePoint.WAIST]?.let { add("${(it * 2.54f).roundToInt()} cm") }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = scheme.tertiaryContainer.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, scheme.tertiary.copy(alpha = 0.20f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(tr(language, "الجسم لا يتغير هنا", "Body stays locked here"), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                tr(language, "الشعر والبشرة والنظارات تغيّر الصورة فقط. قياسات الجسم تبقى من Body Map.", "Hair, skin and glasses only change the portrait. Body measurements stay sourced from Body Map."),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = scheme.tertiary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun hex(value: String): Color = runCatching { Color(android.graphics.Color.parseColor("#$value")) }.getOrDefault(Color.Gray)
private fun tr(language: String, ar: String, en: String): String = if (language == "ar") ar else en
