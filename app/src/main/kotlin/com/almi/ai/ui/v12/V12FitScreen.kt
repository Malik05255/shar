package com.almi.ai.ui.v12

import android.content.Context
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.almi.ai.data.repository.MotionDirection
import com.almi.ai.data.repository.VideoGenerationStatus
import com.almi.ai.ui.tryon.FitPressure
import com.almi.ai.ui.tryon.GarmentSize
import com.almi.ai.ui.tryon.GenerationError
import com.almi.ai.ui.tryon.ProductError
import com.almi.ai.ui.tryon.TryOnUiState
import com.almi.ai.ui.tryon.TryOnViewModel
import java.io.File

private enum class FitInspector { YOU, GARMENT, SIZE }

@Composable
internal fun V12FitScreen(
    viewModel: TryOnViewModel,
    language: String,
    onBack: () -> Unit,
    onAvatar: () -> Unit,
    onAi: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val p = V12Palettes.Fit
    val context = LocalContext.current
    var inspector by rememberSaveable { mutableStateOf<FitInspector?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val personPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistPermission(context, it); viewModel.setPersonImage(it.toString()) }
    }
    val garmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistPermission(context, it); viewModel.setGarmentImage(it.toString()) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { viewModel.setPersonImage(it.toString()) }
    }

    if (state.generatedImage != null) {
        V12FitResult(state, language, onBack = { viewModel.returnToStudio() }, onHome = onBack, onAi = onAi, onMotion = viewModel::setMotion, onVideo = viewModel::generateVideo)
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .statusBarsPadding(),
    ) {
        // Full-stage canvas. The user's body is the UI, not a card inside the UI.
        if (state.personImage != null) {
            AsyncImage(
                model = state.personImage,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)))
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF0F0E0D)))
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 50.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = p.signal.copy(alpha = .13f)) {
                    V12Glyph(V12GlyphType.CAMERA, p.signal, Modifier.padding(18.dp).size(42.dp))
                }
                Text(if (language == "ar") "ابدأ بنفسك" else "START WITH YOU", color = p.ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text(
                    if (language == "ar") "لا توجد فورم. ضع صورة كاملة أو التوأم الرقمي ثم أكمل من الحافة." else "No form. Add a full-body image or your digital twin, then continue from the edge rail.",
                    color = p.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        V12BackControl(
            palette = p,
            label = if (language == "ar") "العوالم" else "WORLDS",
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 74.dp, end = 10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("FIT / 01", color = Color.White.copy(alpha = .38f), fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            EdgeNode("YOU", V12GlyphType.AVATAR, state.personImage != null, inspector == FitInspector.YOU, p) { inspector = if (inspector == FitInspector.YOU) null else FitInspector.YOU }
            EdgeNode("ITEM", V12GlyphType.FIT, state.effectiveGarmentImage != null, inspector == FitInspector.GARMENT, p) { inspector = if (inspector == FitInspector.GARMENT) null else FitInspector.GARMENT }
            EdgeNode("SIZE", V12GlyphType.SIZE, state.selectedGarmentSize != null, inspector == FitInspector.SIZE, p) { inspector = if (inspector == FitInspector.SIZE) null else FitInspector.SIZE }
        }

        // Garment floats on the stage as a physical reference, not another field.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 100.dp)
                .size(width = 108.dp, height = 142.dp)
                .clickable { inspector = FitInspector.GARMENT },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 10.dp, bottomEnd = 30.dp, bottomStart = 12.dp),
            color = Color(0xFFF4EFE8),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .15f)),
            shadowElevation = 12.dp,
        ) {
            if (state.effectiveGarmentImage != null) {
                Box {
                    AsyncImage(state.effectiveGarmentImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (state.productTitle.isNotBlank()) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .64f)).padding(7.dp)) {
                            Text(state.productTitle, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    V12Glyph(V12GlyphType.FIT, Color(0xFF5F5750), Modifier.size(32.dp))
                    Spacer(Modifier.height(7.dp))
                    Text(if (language == "ar") "القطعة" else "GARMENT", color = Color(0xFF5F5750), fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        val ready = state.personImage != null && state.effectiveGarmentImage != null
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .fillMaxWidth()
                .height(68.dp)
                .clickable(enabled = !state.isGeneratingImage) {
                    when {
                        state.personImage == null -> inspector = FitInspector.YOU
                        state.effectiveGarmentImage == null -> inspector = FitInspector.GARMENT
                        state.selectedGarmentSize == null -> inspector = FitInspector.SIZE
                        else -> viewModel.generateImage()
                    }
                },
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 34.dp, bottomEnd = 14.dp, bottomStart = 34.dp),
            color = if (ready) p.signal else p.panel,
            border = BorderStroke(1.dp, if (ready) p.signal else p.edge),
            shadowElevation = 13.dp,
        ) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.isGeneratingImage) {
                    CircularProgressIndicator(progress = { state.imageProgress.coerceIn(0f, 1f) }, modifier = Modifier.size(27.dp), color = p.signalInk, strokeWidth = 2.5.dp)
                } else {
                    V12Glyph(V12GlyphType.FIT, if (ready) p.signalInk else p.muted, Modifier.size(27.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            state.personImage == null -> if (language == "ar") "أضف نفسك" else "ADD YOU"
                            state.effectiveGarmentImage == null -> if (language == "ar") "أضف القطعة" else "ADD GARMENT"
                            state.selectedGarmentSize == null -> if (language == "ar") "اختر المقاس" else "CHOOSE SIZE"
                            state.isGeneratingImage -> if (language == "ar") "نبني الإطلالة" else "BUILDING THE FIT"
                            else -> if (language == "ar") "شغّل التجربة" else "RUN THE FIT"
                        },
                        color = if (ready) p.signalInk else p.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        if (state.isGeneratingImage) "${(state.imageProgress * 100).toInt()}%" else "YOU + GARMENT + SIZE",
                        color = if (ready) p.signalInk.copy(alpha = .60f) else p.muted,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = .8.sp,
                    )
                }
                Text("→", color = if (ready) p.signalInk else p.muted, fontSize = 28.sp, fontWeight = FontWeight.Light)
            }
        }

        AnimatedVisibility(
            visible = inspector != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 91.dp),
                shape = RoundedCornerShape(topStart = 34.dp, topEnd = 14.dp, bottomEnd = 34.dp, bottomStart = 14.dp),
                color = p.panel.copy(alpha = .985f),
                border = BorderStroke(1.dp, p.edge),
                shadowElevation = 20.dp,
            ) {
                when (inspector) {
                    FitInspector.YOU -> YouInspector(language, p, onCamera = {
                        cameraTarget(context)?.let { uri -> cameraUri = uri; camera.launch(uri) }
                    }, onGallery = { personPicker.launch(arrayOf("image/*")) }, onAvatar = onAvatar, onClose = { inspector = null })
                    FitInspector.GARMENT -> GarmentInspector(state, language, p, viewModel, onGallery = { garmentPicker.launch(arrayOf("image/*")) }, onClose = { inspector = null })
                    FitInspector.SIZE -> SizeInspector(state, language, p, viewModel, onClose = { inspector = null })
                    null -> Unit
                }
            }
        }

        when (state.imageError) {
            GenerationError.API_KEY_MISSING -> ErrorFlag(if (language == "ar") "محرك AI يحتاج إعداد" else "AI ENGINE NEEDS SETUP", p, onAi)
            GenerationError.REQUEST_FAILED -> ErrorFlag(if (language == "ar") "فشل التوليد — افتح AI" else "GENERATION FAILED — OPEN AI", p, onAi)
            GenerationError.NONE -> Unit
        }
    }
}

@Composable
private fun EdgeNode(label: String, glyph: V12GlyphType, ready: Boolean, active: Boolean, p: V12Palette, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(width = 62.dp, height = 58.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 21.dp, bottomStart = 21.dp, topEnd = 7.dp, bottomEnd = 7.dp),
        color = if (active) p.signal else Color.Black.copy(alpha = .58f),
        border = BorderStroke(1.dp, if (active) p.signal else Color.White.copy(alpha = .10f)),
    ) {
        Column(Modifier.fillMaxSize().padding(7.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            V12Glyph(glyph, if (active) p.signalInk else if (ready) p.signal else Color.White.copy(alpha = .62f), Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, color = if (active) p.signalInk else Color.White.copy(alpha = .70f), fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
        }
    }
}

@Composable
private fun YouInspector(language: String, p: V12Palette, onCamera: () -> Unit, onGallery: () -> Unit, onAvatar: () -> Unit, onClose: () -> Unit) {
    InspectorFrame("01 / YOU", if (language == "ar") "من الذي سيجرب؟" else "WHO IS TRYING IT?", p, onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InspectorChoice(if (language == "ar") "كاميرا" else "CAMERA", V12GlyphType.CAMERA, p, Modifier.weight(1f), onCamera)
            InspectorChoice(if (language == "ar") "صورة" else "PHOTO", V12GlyphType.IMAGE, p, Modifier.weight(1f), onGallery)
        }
        InspectorChoice(if (language == "ar") "استخدم شخصيتي ثلاثية الأبعاد" else "USE MY 3D CHARACTER", V12GlyphType.AVATAR, p, Modifier.fillMaxWidth(), onAvatar)
    }
}

@Composable
private fun GarmentInspector(state: TryOnUiState, language: String, p: V12Palette, viewModel: TryOnViewModel, onGallery: () -> Unit, onClose: () -> Unit) {
    InspectorFrame("02 / GARMENT", if (language == "ar") "أدخل القطعة" else "BRING THE GARMENT IN", p, onClose) {
        OutlinedTextField(
            value = state.productUrl,
            onValueChange = viewModel::setProductUrl,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text(if (language == "ar") "رابط المنتج" else "PRODUCT LINK") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InspectorChoice(if (language == "ar") "استيراد الرابط" else "IMPORT LINK", V12GlyphType.LINK, p, Modifier.weight(1f)) { viewModel.loadProduct() }
            InspectorChoice(if (language == "ar") "صورة القطعة" else "IMAGE", V12GlyphType.IMAGE, p, Modifier.weight(1f), onGallery)
        }
        if (state.productTitle.isNotBlank()) {
            Text(state.productTitle, color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when (state.productError) {
            ProductError.EMPTY_URL -> Text(if (language == "ar") "ألصق رابطًا أولًا" else "PASTE A LINK FIRST", color = p.signal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            ProductError.UNAVAILABLE -> Text(if (language == "ar") "تعذر قراءة الرابط" else "LINK COULD NOT BE READ", color = p.signal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            ProductError.IMAGE_NOT_FOUND -> Text(if (language == "ar") "لم نجد صورة مناسبة" else "NO USABLE IMAGE FOUND", color = p.signal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            ProductError.NONE -> Unit
        }
    }
}

@Composable
private fun SizeInspector(state: TryOnUiState, language: String, p: V12Palette, viewModel: TryOnViewModel, onClose: () -> Unit) {
    InspectorFrame("03 / SIZE", if (language == "ar") "اختر مقاس المتجر" else "PICK THE RETAIL SIZE", p, onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.availableGarmentSizes.forEach { size ->
                val active = state.selectedGarmentSize == size
                Surface(
                    modifier = Modifier.size(width = 64.dp, height = 56.dp).clickable { viewModel.setGarmentSize(size) },
                    shape = RoundedCornerShape(if (active) 28.dp else 13.dp),
                    color = if (active) p.signal else p.background,
                    border = BorderStroke(1.dp, if (active) p.signal else p.edge),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(size.label, color = if (active) p.signalInk else p.ink, fontSize = 12.sp, fontWeight = FontWeight.Black) }
                }
            }
        }
        state.fitSimulation?.let { fit ->
            Text(
                "${fit.size.label} / ${pressureLabel(fit.overallPressure, language)}",
                color = p.signal,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                if (language == "ar") "التقدير يصف ضغط المقاس؛ لا يغير شكل جسمك لتحسين الصورة." else "This describes fit pressure. ALMI does not reshape your body to flatter the output.",
                color = p.muted,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
        }
    }
}

@Composable
private fun InspectorFrame(code: String, title: String, p: V12Palette, onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text(code, color = p.signal, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text(title, color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
            Surface(Modifier.size(36.dp).clickable(onClick = onClose), CircleShape, color = p.background) {
                Box(contentAlignment = Alignment.Center) { Text("×", color = p.ink, fontSize = 21.sp) }
            }
        }
        content()
    }
}

@Composable
private fun InspectorChoice(text: String, glyph: V12GlyphType, p: V12Palette, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(58.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 10.dp, bottomEnd = 18.dp, bottomStart = 10.dp),
        color = p.background,
        border = BorderStroke(1.dp, p.edge),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            V12Glyph(glyph, p.signal, Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
            Text(text, color = p.ink, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun V12FitResult(
    state: TryOnUiState,
    language: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onAi: () -> Unit,
    onMotion: (MotionDirection) -> Unit,
    onVideo: () -> Unit,
) {
    val p = V12Palettes.Fit
    var before by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
        if (state.generatedVideo != null && !before) {
            AndroidView(
                factory = { ctx -> VideoView(ctx).apply { setOnPreparedListener { m -> m.isLooping = true; start() } } },
                update = { view -> if (view.tag != state.generatedVideo) { view.tag = state.generatedVideo; view.setVideoURI(Uri.parse(state.generatedVideo)); view.start() } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(if (before) state.personImage else state.generatedImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .10f)))

        V12BackControl(p, if (language == "ar") "تعديل" else "EDIT", onBack, Modifier.align(Alignment.TopStart).padding(12.dp))
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = .54f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
        ) {
            Row {
                Text(if (language == "ar") "قبل" else "BEFORE", Modifier.clickable { before = true }.padding(horizontal = 12.dp, vertical = 9.dp), color = if (before) p.signal else Color.White.copy(alpha = .48f), fontSize = 8.sp, fontWeight = FontWeight.Black)
                Text(if (language == "ar") "بعد" else "AFTER", Modifier.clickable { before = false }.padding(horizontal = 12.dp, vertical = 9.dp), color = if (!before) p.signal else Color.White.copy(alpha = .48f), fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 14.dp, bottomEnd = 34.dp, bottomStart = 14.dp),
            color = Color.Black.copy(alpha = .76f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("FIT RESULT", color = p.signal, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                        Text(state.fitSimulation?.let { "${it.size.label} / ${pressureLabel(it.overallPressure, language)}" } ?: "VISUAL RESULT", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    Text("↗", Modifier.clickable(onClick = onHome), color = Color.White, fontSize = 24.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MotionChip(MotionDirection.TURN, state.motion, V12GlyphType.TURN, if (language == "ar") "دوران" else "TURN", p, Modifier.weight(1f), onMotion)
                    MotionChip(MotionDirection.WALK, state.motion, V12GlyphType.WALK, if (language == "ar") "مشي" else "WALK", p, Modifier.weight(1f), onMotion)
                    MotionChip(MotionDirection.DETAIL, state.motion, V12GlyphType.DETAIL, if (language == "ar") "تفاصيل" else "DETAIL", p, Modifier.weight(1f), onMotion)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().height(48.dp).clickable(enabled = !state.isGeneratingVideo && state.generatedVideo == null, onClick = onVideo),
                    shape = RoundedCornerShape(999.dp),
                    color = p.signal,
                ) {
                    Row(Modifier.padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        V12Glyph(V12GlyphType.WALK, p.signalInk, Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(videoLabel(state, language), color = p.signalInk, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
                if (state.videoError) Text(if (language == "ar") "فشل إنشاء الفيديو — افحص AI" else "VIDEO FAILED — CHECK AI", Modifier.clickable(onClick = onAi), color = p.signal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MotionChip(value: MotionDirection, current: MotionDirection, glyph: V12GlyphType, text: String, p: V12Palette, modifier: Modifier, onMotion: (MotionDirection) -> Unit) {
    val active = value == current
    Surface(modifier.height(44.dp).clickable { onMotion(value) }, RoundedCornerShape(999.dp), color = if (active) p.signal else Color.White.copy(alpha = .07f), border = BorderStroke(1.dp, if (active) p.signal else Color.White.copy(alpha = .10f))) {
        Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            V12Glyph(glyph, if (active) p.signalInk else Color.White.copy(alpha = .76f), Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = if (active) p.signalInk else Color.White.copy(alpha = .76f), fontSize = 8.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ErrorFlag(text: String, p: V12Palette, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.padding(top = 80.dp, start = 12.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = p.signal,
    ) { Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = p.signalInk, fontSize = 8.sp, fontWeight = FontWeight.Black) }
}

private fun pressureLabel(value: FitPressure, language: String): String = when (value) {
    FitPressure.VERY_TIGHT -> if (language == "ar") "شديد الضيق" else "VERY TIGHT"
    FitPressure.TIGHT -> if (language == "ar") "ضيق" else "TIGHT"
    FitPressure.CLOSE -> if (language == "ar") "ملاصق" else "CLOSE"
    FitPressure.REGULAR -> if (language == "ar") "مناسب" else "REGULAR"
    FitPressure.LOOSE -> if (language == "ar") "واسع" else "LOOSE"
    FitPressure.UNKNOWN -> if (language == "ar") "تقديري" else "ESTIMATE"
}

private fun videoLabel(state: TryOnUiState, language: String): String = when {
    state.generatedVideo != null -> if (language == "ar") "الفيديو جاهز" else "VIDEO READY"
    state.videoStatus == VideoGenerationStatus.SUBMITTING -> if (language == "ar") "إرسال…" else "SUBMITTING…"
    state.videoStatus == VideoGenerationStatus.PROCESSING -> if (language == "ar") "معالجة…" else "PROCESSING…"
    state.videoStatus == VideoGenerationStatus.DOWNLOADING -> if (language == "ar") "تجهيز…" else "PREPARING…"
    else -> if (language == "ar") "حوّل النتيجة إلى حركة" else "ANIMATE THE RESULT"
}

private fun cameraTarget(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "almi_v12_${System.currentTimeMillis()}.jpg"))
}.getOrNull()

private fun persistPermission(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}
