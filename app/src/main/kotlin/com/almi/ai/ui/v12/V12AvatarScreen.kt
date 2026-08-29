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
import kotlinx.coroutines.delay

private enum class AvatarRail { SKIN, HAIR, COLOR, FACE }

private val MaleAccent = Color(0xFF42B9EC)
private val MaleWash = Color(0xFFDDF7FF)
private val FemaleAccent = Color(0xFFFF7FA1)
private val FemaleWash = Color(0xFFFFEAF0)

@Composable
internal fun V12AvatarScreen(
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
    val p = V12Palettes.Avatar
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val selectedPresentation = selected?.let { runCatching { AvatarPresentation.valueOf(it) }.getOrNull() }
    var rail by rememberSaveable { mutableStateOf(AvatarRail.SKIN) }
    var selectedRuntime by remember { mutableStateOf<V12AvatarRuntime?>(null) }
    var controls by remember { mutableStateOf(false) }

    LaunchedEffect(confirmed, selectedPresentation, selectedRuntime) {
        controls = false
        val runtime = selectedRuntime
        if (confirmed && selectedPresentation != null && runtime != null) {
            onPresentation(selectedPresentation)
            runtime.start()
            runtime.faceFront()
            runtime.playWalkIn(fromRight = selectedPresentation == AvatarPresentation.FEMININE, durationMs = 760L)
            delay(620)
            controls = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF7FCFF),
                        Color(0xFFEAF7FF),
                        Color(0xFFF7FBFF),
                    )
                )
            )
            .statusBarsPadding()
    ) {
        IceGrid(Modifier.fillMaxSize())

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "ALMI / ${if (confirmed) "AVATAR LAB" else "IDENTITY"}",
                color = Color(0xFF82B9DA),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.4.sp,
            )
            Text(
                if (confirmed) {
                    if (language == "ar") "صمّم شخصيتك" else "BUILD YOUR AVATAR"
                } else {
                    if (language == "ar") "اختر شخصيتك" else "CHOOSE YOUR CHARACTER"
                },
                color = p.ink,
                fontSize = 27.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-.6).sp,
            )
        }

        V12BackControl(
            palette = p,
            label = if (language == "ar") "رجوع" else "BACK",
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 8.dp),
        )

        if (!confirmed) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(top = 88.dp, bottom = 90.dp, start = 14.dp, end = 14.dp)
            ) {
                val cardWidth = maxWidth * .475f

                GenderCard(
                    modifier = Modifier.align(Alignment.CenterStart).width(cardWidth).fillMaxSize(.96f),
                    presentation = AvatarPresentation.MASCULINE,
                    appearance = appearance.copy(presentation = AvatarPresentation.MASCULINE),
                    title = if (language == "ar") "ذكر" else "MALE",
                    symbol = "♂",
                    accent = MaleAccent,
                    wash = MaleWash,
                    selected = selectedPresentation == AvatarPresentation.MASCULINE,
                    onClick = { selected = AvatarPresentation.MASCULINE.name },
                ) { runtime ->
                    if (selectedPresentation == AvatarPresentation.MASCULINE) selectedRuntime = runtime
                }

                GenderCard(
                    modifier = Modifier.align(Alignment.CenterEnd).width(cardWidth).fillMaxSize(.96f),
                    presentation = AvatarPresentation.FEMININE,
                    appearance = appearance.copy(presentation = AvatarPresentation.FEMININE),
                    title = if (language == "ar") "أنثى" else "FEMALE",
                    symbol = "♀",
                    accent = FemaleAccent,
                    wash = FemaleWash,
                    selected = selectedPresentation == AvatarPresentation.FEMININE,
                    onClick = { selected = AvatarPresentation.FEMININE.name },
                ) { runtime ->
                    if (selectedPresentation == AvatarPresentation.FEMININE) selectedRuntime = runtime
                }
            }

            val canContinue = selectedPresentation != null
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 18.dp)
                    .fillMaxWidth()
                    .height(58.dp)
                    .then(if (canContinue) Modifier.clickable { confirmed = true } else Modifier),
                shape = RoundedCornerShape(999.dp),
                color = if (canContinue) p.signal else Color(0xFFD7E4ED),
                border = BorderStroke(1.dp, if (canContinue) p.signal.copy(alpha = .45f) else Color(0xFFC9D8E2)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (language == "ar") "التالي" else "NEXT",
                        color = if (canContinue) Color.White else Color(0xFF8DA3B3),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        } else if (selectedPresentation != null) {
            val accent = if (selectedPresentation == AvatarPresentation.MASCULINE) MaleAccent else FemaleAccent
            val wash = if (selectedPresentation == AvatarPresentation.MASCULINE) MaleWash else FemaleWash

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 82.dp, bottom = if (controls) 98.dp else 16.dp, start = 18.dp, end = 18.dp),
                shape = RoundedCornerShape(34.dp),
                color = wash.copy(alpha = .72f),
                border = BorderStroke(1.5.dp, accent.copy(alpha = .36f)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    AvatarViewport(
                        presentation = selectedPresentation,
                        appearance = appearance.copy(presentation = selectedPresentation),
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        onRuntime = { selectedRuntime = it },
                    )

                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(alpha = .90f),
                        border = BorderStroke(1.dp, accent.copy(alpha = .28f)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (selectedPresentation == AvatarPresentation.MASCULINE) "♂" else "♀",
                                color = accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (selectedPresentation == AvatarPresentation.MASCULINE) {
                                    if (language == "ar") "شخصية ذكر" else "MALE AVATAR"
                                } else {
                                    if (language == "ar") "شخصية أنثى" else "FEMALE AVATAR"
                                },
                                color = p.ink,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, top = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    RailNode("SKIN", V12GlyphType.AVATAR, rail == AvatarRail.SKIN, p, accent) { rail = AvatarRail.SKIN }
                    RailNode("HAIR", V12GlyphType.FIT, rail == AvatarRail.HAIR, p, accent) { rail = AvatarRail.HAIR }
                    RailNode("COLOR", V12GlyphType.THEME, rail == AvatarRail.COLOR, p, accent) { rail = AvatarRail.COLOR }
                    RailNode("FACE", V12GlyphType.DETAIL, rail == AvatarRail.FACE, p, accent) { rail = AvatarRail.FACE }
                }
            }

            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(end = 8.dp, top = 120.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RoundAction("360", V12GlyphType.TURN, p, accent) { selectedRuntime?.playTurntable() }
                    RoundAction(if (language == "ar") "بدّل" else "SWAP", V12GlyphType.RESET, p, accent) {
                        controls = false
                        confirmed = false
                        selected = null
                        selectedRuntime = null
                    }
                }
            }

            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White.copy(alpha = .97f),
                    border = BorderStroke(1.dp, p.edge),
                ) {
                    Row(
                        Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(280.dp)) {
                            when (rail) {
                                AvatarRail.SKIN -> ColorChoices(
                                    listOf("F3D0BA", "E4B58F", "CF936B", "B97752", "8E583D", "603A2D"),
                                    appearance.skinColor,
                                    accent,
                                    p.edge,
                                    onSkinColor,
                                )

                                AvatarRail.HAIR -> TextChoices(
                                    listOf(
                                        "bald" to (if (language == "ar") "بدون" else "BALD"),
                                        "shortFlat" to (if (language == "ar") "قصير" else "SHORT"),
                                        "shortCurly" to (if (language == "ar") "كيرلي" else "CURLY"),
                                        "bob" to "BOB",
                                        "longButNotTooLong" to (if (language == "ar") "طويل" else "LONG"),
                                    ),
                                    appearance.hairVariant,
                                    p,
                                    accent,
                                    onHair,
                                )

                                AvatarRail.COLOR -> ColorChoices(
                                    listOf("151210", "281916", "4D3025", "774227", "A46C3E", "D0B184"),
                                    appearance.hairColor,
                                    accent,
                                    p.edge,
                                    onHairColor,
                                )

                                AvatarRail.FACE -> TextChoices(
                                    listOf(
                                        "eyes:default" to (if (language == "ar") "طبيعي" else "NATURAL"),
                                        "eyes:wide" to (if (language == "ar") "عين واسعة" else "WIDE"),
                                        "eyes:sharp" to (if (language == "ar") "نظرة حادة" else "SHARP"),
                                        "brow:defined" to (if (language == "ar") "حاجب" else "BROW"),
                                        "mouth:smile" to (if (language == "ar") "ابتسامة" else "SMILE"),
                                        "mouth:full" to (if (language == "ar") "شفاه" else "LIPS"),
                                    ),
                                    currentFaceKey(appearance),
                                    p,
                                    accent,
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
                            modifier = Modifier.height(56.dp).width(72.dp).clickable(onClick = onComplete),
                            shape = RoundedCornerShape(20.dp),
                            color = accent,
                        ) {
                            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                                Text("✓", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                                Text(
                                    if (language == "ar") "اعتماد" else "USE",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                )
                            }
                        }
                    }
                }
            }

            if (controls && (digitalTwinSnapshotUri != null || bodyProfile.hasExplicitHeight)) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp),
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
private fun IceGrid(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val step = 28.dp.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(Color.White.copy(alpha = .42f), androidx.compose.ui.geometry.Offset(x, size.height * .18f), androidx.compose.ui.geometry.Offset(x, size.height * .84f), 1f)
            x += step
        }
        var y = size.height * .18f
        while (y <= size.height * .84f) {
            drawLine(Color.White.copy(alpha = .42f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
            y += step
        }
    }
}

@Composable
private fun GenderCard(
    modifier: Modifier,
    presentation: AvatarPresentation,
    appearance: AvatarAppearance,
    title: String,
    symbol: String,
    accent: Color,
    wash: Color,
    selected: Boolean,
    onClick: () -> Unit,
    onRuntime: (V12AvatarRuntime) -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        color = wash.copy(alpha = .86f),
        border = BorderStroke(if (selected) 3.dp else 1.5.dp, if (selected) accent else accent.copy(alpha = .34f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            AvatarViewport(
                presentation = presentation,
                appearance = appearance,
                modifier = Modifier.fillMaxSize().padding(top = 46.dp, bottom = 4.dp, start = 3.dp, end = 3.dp),
                onRuntime = onRuntime,
            )

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).fillMaxWidth(.72f).height(42.dp),
                shape = RoundedCornerShape(999.dp),
                color = accent,
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(symbol, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }

            if (selected) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(2.dp, accent),
                ) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Text("✓", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarViewport(
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
private fun RailNode(
    label: String,
    glyph: V12GlyphType,
    active: Boolean,
    p: V12Palette,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(width = 58.dp, height = if (active) 66.dp else 52.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (active) accent else Color.White.copy(alpha = .94f),
        border = BorderStroke(1.dp, if (active) accent else p.edge),
    ) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            V12Glyph(glyph, if (active) Color.White else accent, Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = if (active) Color.White else p.muted,
                fontSize = 6.8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = .5.sp,
            )
        }
    }
}

@Composable
private fun RoundAction(label: String, glyph: V12GlyphType, p: V12Palette, accent: Color, onClick: () -> Unit) {
    Surface(
        Modifier.size(54.dp).clickable(onClick = onClick),
        CircleShape,
        color = Color.White.copy(alpha = .94f),
        border = BorderStroke(1.dp, p.edge),
    ) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            V12Glyph(glyph, accent, Modifier.size(18.dp))
            Text(label, color = p.muted, fontSize = 6.5.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ColorChoices(
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
private fun TextChoices(
    options: List<Pair<String, String>>,
    current: String,
    p: V12Palette,
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
                border = BorderStroke(1.dp, if (active) accent else p.edge),
            ) {
                Box(Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = if (active) Color.White else p.ink,
                        fontSize = 7.8.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun currentFaceKey(appearance: AvatarAppearance): String = when {
    appearance.eyesVariant != "default" -> "eyes:${appearance.eyesVariant}"
    appearance.eyebrowsVariant != "default" -> "brow:${appearance.eyebrowsVariant}"
    else -> "mouth:${appearance.mouthVariant}"
}
