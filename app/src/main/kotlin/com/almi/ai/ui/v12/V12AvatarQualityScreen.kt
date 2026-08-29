package com.almi.ai.ui.v12

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.almi.ai.data.preferences.AvatarAppearance
import com.almi.ai.data.preferences.AvatarPresentation
import com.almi.ai.data.preferences.BodyProfile

private enum class QualityAvatarRail { SKIN, HAIR, COLOR, FACE }

private val QualityMaleAccent = Color(0xFF38B7EC)
private val QualityMaleWash = Color(0xFFDDF7FF)
private val QualityFemaleAccent = Color(0xFFFF799D)
private val QualityFemaleWash = Color(0xFFFFE8EF)

/**
 * v12 quality-first avatar experience.
 *
 * Selection uses the dedicated PBR male/female bodies and real skeleton idle clips. The editor is
 * intentionally kept as a separate stage so the current customization controls remain functional
 * while the higher-fidelity multi-part digital-human editor is integrated.
 */
@Composable
internal fun V12AvatarQualityScreen(
    language: String,
    appearance: AvatarAppearance,
    bodyProfile: BodyProfile,
    digitalTwinSnapshotUri: String?,
    onPresentation: (AvatarPresentation) -> Unit,
    onHair: (String) -> Unit,
    onHairColor: (String) -> Unit,
    onSkinColor: (String) -> Unit,
    onEyes: (String) -> Unit,
    onEyebrows: (String) -> Unit,
    onMouth: (String) -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    val palette = V12Palettes.Avatar
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var rail by rememberSaveable { mutableStateOf(QualityAvatarRail.SKIN) }
    var editorRuntime by remember { mutableStateOf<V12AvatarRuntime?>(null) }
    val selected = selectedName?.let { runCatching { AvatarPresentation.valueOf(it) }.getOrNull() }

    LaunchedEffect(editing, selected, editorRuntime) {
        if (editing && selected != null) {
            onPresentation(selected)
            editorRuntime?.start()
            editorRuntime?.faceFront()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFFBFEFF),
                        Color(0xFFE9F7FF),
                        Color(0xFFF8FCFF),
                    )
                )
            )
            .statusBarsPadding()
    ) {
        QualityIceGrid(Modifier.fillMaxSize())

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (editing) "ALMI / AVATAR LAB" else "ALMI / IDENTITY",
                color = Color(0xFF79B7D9),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.4.sp,
            )
            Text(
                if (editing) {
                    if (language == "ar") "صمّم شخصيتك" else "BUILD YOUR AVATAR"
                } else {
                    if (language == "ar") "اختر شخصيتك" else "CHOOSE YOUR CHARACTER"
                },
                color = palette.ink,
                fontSize = 27.sp,
                fontWeight = FontWeight.Black,
            )
        }

        V12BackControl(
            palette = palette,
            label = if (editing) {
                if (language == "ar") "الاختيار" else "CHOOSE"
            } else {
                if (language == "ar") "رجوع" else "BACK"
            },
            onBack = {
                if (editing) {
                    editing = false
                    editorRuntime = null
                } else {
                    onBack()
                }
            },
            modifier = Modifier.align(Alignment.TopStart).padding(start = 13.dp, top = 7.dp),
        )

        if (!editing) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(top = 86.dp, bottom = 92.dp, start = 12.dp, end = 12.dp)
            ) {
                val cardWidth = maxWidth * .48f
                QualityGenderCard(
                    modifier = Modifier.align(Alignment.CenterStart).width(cardWidth).fillMaxSize(.97f),
                    presentation = AvatarPresentation.MASCULINE,
                    title = if (language == "ar") "ذكر" else "MALE",
                    accent = QualityMaleAccent,
                    wash = QualityMaleWash,
                    selected = selected == AvatarPresentation.MASCULINE,
                    onClick = { selectedName = AvatarPresentation.MASCULINE.name },
                )
                QualityGenderCard(
                    modifier = Modifier.align(Alignment.CenterEnd).width(cardWidth).fillMaxSize(.97f),
                    presentation = AvatarPresentation.FEMININE,
                    title = if (language == "ar") "أنثى" else "FEMALE",
                    accent = QualityFemaleAccent,
                    wash = QualityFemaleWash,
                    selected = selected == AvatarPresentation.FEMININE,
                    onClick = { selectedName = AvatarPresentation.FEMININE.name },
                )
            }

            val enabled = selected != null
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 22.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .height(60.dp)
                    .then(if (enabled) Modifier.clickable { editing = true } else Modifier),
                shape = RoundedCornerShape(999.dp),
                color = if (enabled) Color(0xFF4AADEA) else Color(0xFFD5E3EC),
                border = BorderStroke(1.dp, if (enabled) Color(0xFF85C9F1) else Color(0xFFC7D7E1)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (language == "ar") "التالي" else "NEXT",
                        color = if (enabled) Color.White else Color(0xFF8BA1B0),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        } else if (selected != null) {
            val accent = if (selected == AvatarPresentation.MASCULINE) QualityMaleAccent else QualityFemaleAccent
            val wash = if (selected == AvatarPresentation.MASCULINE) QualityMaleWash else QualityFemaleWash

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 82.dp, bottom = 98.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(34.dp),
                color = wash.copy(alpha = .72f),
                border = BorderStroke(1.5.dp, accent.copy(alpha = .36f)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    QualityEditorViewport(
                        presentation = selected,
                        appearance = appearance.copy(presentation = selected),
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        onRuntime = { editorRuntime = it },
                    )

                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(alpha = .92f),
                        border = BorderStroke(1.dp, accent.copy(alpha = .28f)),
                    ) {
                        Text(
                            if (selected == AvatarPresentation.MASCULINE) {
                                if (language == "ar") "♂  شخصية ذكر" else "♂  MALE AVATAR"
                            } else {
                                if (language == "ar") "♀  شخصية أنثى" else "♀  FEMALE AVATAR"
                            },
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            color = palette.ink,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 7.dp, top = 116.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                QualityRailNode("SKIN", V12GlyphType.AVATAR, rail == QualityAvatarRail.SKIN, palette, accent) { rail = QualityAvatarRail.SKIN }
                QualityRailNode("HAIR", V12GlyphType.FIT, rail == QualityAvatarRail.HAIR, palette, accent) { rail = QualityAvatarRail.HAIR }
                QualityRailNode("COLOR", V12GlyphType.THEME, rail == QualityAvatarRail.COLOR, palette, accent) { rail = QualityAvatarRail.COLOR }
                QualityRailNode("FACE", V12GlyphType.DETAIL, rail == QualityAvatarRail.FACE, palette, accent) { rail = QualityAvatarRail.FACE }
            }

            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp, top = 116.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QualityRoundAction("360", V12GlyphType.TURN, palette, accent) { editorRuntime?.playTurntable() }
                QualityRoundAction(if (language == "ar") "بدّل" else "SWAP", V12GlyphType.RESET, palette, accent) {
                    editing = false
                    editorRuntime = null
                }
            }

            AnimatedVisibility(
                visible = editorRuntime != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 9.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White.copy(alpha = .98f),
                    border = BorderStroke(1.dp, palette.edge),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            when (rail) {
                                QualityAvatarRail.SKIN -> QualityColorChoices(
                                    values = listOf("F3D0BA", "E4B58F", "CF936B", "B97752", "8E583D", "603A2D"),
                                    current = appearance.skinColor,
                                    accent = accent,
                                    edge = palette.edge,
                                    onSelect = onSkinColor,
                                )
                                QualityAvatarRail.HAIR -> QualityTextChoices(
                                    options = listOf(
                                        "bald" to (if (language == "ar") "بدون" else "BALD"),
                                        "shortFlat" to (if (language == "ar") "قصير" else "SHORT"),
                                        "shortCurly" to (if (language == "ar") "كيرلي" else "CURLY"),
                                        "bob" to "BOB",
                                        "longButNotTooLong" to (if (language == "ar") "طويل" else "LONG"),
                                    ),
                                    current = appearance.hairVariant,
                                    palette = palette,
                                    accent = accent,
                                    onSelect = onHair,
                                )
                                QualityAvatarRail.COLOR -> QualityColorChoices(
                                    values = listOf("151210", "281916", "4D3025", "774227", "A46C3E", "D0B184"),
                                    current = appearance.hairColor,
                                    accent = accent,
                                    edge = palette.edge,
                                    onSelect = onHairColor,
                                )
                                QualityAvatarRail.FACE -> QualityTextChoices(
                                    options = listOf(
                                        "eyes:default" to (if (language == "ar") "طبيعي" else "NATURAL"),
                                        "eyes:wide" to (if (language == "ar") "عين واسعة" else "WIDE"),
                                        "eyes:sharp" to (if (language == "ar") "نظرة حادة" else "SHARP"),
                                        "brow:defined" to (if (language == "ar") "حاجب" else "BROW"),
                                        "mouth:smile" to (if (language == "ar") "ابتسامة" else "SMILE"),
                                        "mouth:full" to (if (language == "ar") "شفاه" else "LIPS"),
                                    ),
                                    current = qualityFaceKey(appearance),
                                    palette = palette,
                                    accent = accent,
                                ) { key ->
                                    when {
                                        key.startsWith("eyes:") -> onEyes(key.substringAfter(':'))
                                        key.startsWith("brow:") -> onEyebrows(key.substringAfter(':'))
                                        key.startsWith("mouth:") -> onMouth(key.substringAfter(':'))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))
                        Surface(
                            modifier = Modifier.size(width = 76.dp, height = 56.dp).clickable(onClick = onComplete),
                            shape = RoundedCornerShape(20.dp),
                            color = accent,
                        ) {
                            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                                Text("✓", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                                Text(if (language == "ar") "اعتماد" else "USE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }

            if (digitalTwinSnapshotUri != null || bodyProfile.hasExplicitHeight) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 77.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = .92f),
                    border = BorderStroke(1.dp, accent.copy(alpha = .30f)),
                ) {
                    Text(
                        "BODY SYNC / ON",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = accent,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityGenderCard(
    modifier: Modifier,
    presentation: AvatarPresentation,
    title: String,
    accent: Color,
    wash: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        color = wash.copy(alpha = .88f),
        border = BorderStroke(if (selected) 3.dp else 1.4.dp, if (selected) accent else accent.copy(alpha = .30f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            QualityHeroViewport(
                presentation = presentation,
                modifier = Modifier.fillMaxSize().padding(top = 48.dp, bottom = 8.dp, start = 3.dp, end = 3.dp),
            )

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).fillMaxWidth(.76f).height(44.dp),
                shape = RoundedCornerShape(999.dp),
                color = accent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "${if (presentation == AvatarPresentation.MASCULINE) "♂" else "♀"}  $title",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = .91f),
                border = BorderStroke(1.dp, accent.copy(alpha = .32f)),
            ) {
                Text(
                    if (selected) "✓  SELECTED" else "LIVE 3D",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = accent,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = .6.sp,
                )
            }
        }
    }
}

@Composable
private fun QualityHeroViewport(presentation: AvatarPresentation, modifier: Modifier) {
    var runtime by remember(presentation) { mutableStateOf<V12AvatarHeroRuntime?>(null) }
    DisposableEffect(presentation) { onDispose { runtime?.stop() } }
    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).also { surface ->
                    V12AvatarHeroRuntime(context, surface, presentation).also {
                        runtime = it
                        it.initialize()
                        it.start()
                    }
                }
            },
        )
    }
}

@Composable
private fun QualityEditorViewport(
    presentation: AvatarPresentation,
    appearance: AvatarAppearance,
    modifier: Modifier,
    onRuntime: (V12AvatarRuntime) -> Unit,
) {
    var runtime by remember(presentation) { mutableStateOf<V12AvatarRuntime?>(null) }
    DisposableEffect(presentation) { onDispose { runtime?.stop() } }
    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).also { surface ->
                    V12AvatarRuntime(context, surface, presentation, appearance).also {
                        runtime = it
                        onRuntime(it)
                        it.initialize()
                        it.start()
                    }
                }
            },
            update = { runtime?.update(presentation, appearance) },
        )
    }
}

@Composable
private fun QualityIceGrid(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val step = 30.dp.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(Color.White.copy(alpha = .46f), androidx.compose.ui.geometry.Offset(x, size.height * .17f), androidx.compose.ui.geometry.Offset(x, size.height * .85f), 1f)
            x += step
        }
        var y = size.height * .17f
        while (y <= size.height * .85f) {
            drawLine(Color.White.copy(alpha = .46f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
            y += step
        }
    }
}

@Composable
private fun QualityRailNode(
    label: String,
    glyph: V12GlyphType,
    active: Boolean,
    palette: V12Palette,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(width = 58.dp, height = if (active) 66.dp else 52.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (active) accent else Color.White.copy(alpha = .95f),
        border = BorderStroke(1.dp, if (active) accent else palette.edge),
    ) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            V12Glyph(glyph, if (active) Color.White else accent, Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, color = if (active) Color.White else palette.muted, fontSize = 6.8.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun QualityRoundAction(
    label: String,
    glyph: V12GlyphType,
    palette: V12Palette,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        Modifier.size(54.dp).clickable(onClick = onClick),
        CircleShape,
        color = Color.White.copy(alpha = .95f),
        border = BorderStroke(1.dp, palette.edge),
    ) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            V12Glyph(glyph, accent, Modifier.size(18.dp))
            Text(label, color = palette.muted, fontSize = 6.5.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun QualityColorChoices(
    values: List<String>,
    current: String,
    accent: Color,
    edge: Color,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            val active = current.equals(value, true)
            Surface(
                modifier = Modifier.size(if (active) 48.dp else 40.dp).clickable { onSelect(value) },
                shape = CircleShape,
                color = runCatching { Color(android.graphics.Color.parseColor("#$value")) }.getOrDefault(Color.Gray),
                border = BorderStroke(if (active) 3.dp else 1.dp, if (active) accent else edge),
            ) {}
        }
    }
}

@Composable
private fun QualityTextChoices(
    options: List<Pair<String, String>>,
    current: String,
    palette: V12Palette,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (value, label) ->
            val active = current == value
            Surface(
                modifier = Modifier.height(42.dp).clickable { onSelect(value) },
                shape = RoundedCornerShape(999.dp),
                color = if (active) accent else Color(0xFFF5FAFD),
                border = BorderStroke(1.dp, if (active) accent else palette.edge),
            ) {
                Box(Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (active) Color.White else palette.ink, fontSize = 7.8.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun qualityFaceKey(appearance: AvatarAppearance): String = when {
    appearance.eyesVariant != "default" -> "eyes:${appearance.eyesVariant}"
    appearance.eyebrowsVariant != "default" -> "brow:${appearance.eyebrowsVariant}"
    else -> "mouth:${appearance.mouthVariant}"
}
