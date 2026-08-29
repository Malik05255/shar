package com.almi.ai.ui.tryon

import android.content.Context
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.almi.ai.data.repository.MotionDirection
import com.almi.ai.data.repository.VideoGenerationStatus
import java.io.File

@Composable
fun AlmiV7StudioScreen(
    viewModel: TryOnViewModel,
    language: String,
    onOpenAi: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val personPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistPermission(context, it)
            viewModel.setPersonImage(it.toString())
        }
    }
    val garmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistPermission(context, it)
            viewModel.setGarmentImage(it.toString())
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraUri?.let { viewModel.setPersonImage(it.toString()) }
    }

    if (state.generatedImage != null) {
        ResultScreen(
            state = state,
            language = language,
            onBack = viewModel::returnToStudio,
            onReset = viewModel::reset,
            onOpenAi = onOpenAi,
            onMotion = viewModel::setMotion,
            onVideo = viewModel::generateVideo,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StudioHeader(language)
        Text(
            tr(language, "جرّب المقاس قبل أن تدفع", "Try the size before checkout"),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            tr(
                language,
                "مرجع جسم + قطعة + مقاس. الباقي يتولاه ALMI بدون تغيير شكل جسمك حتى تبدو القطعة مناسبة.",
                "Body reference + garment + size. ALMI handles the rest without reshaping you just to make the garment fit.",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )

        MediaStage(
            personImage = state.personImage,
            garmentImage = state.effectiveGarmentImage,
            language = language,
            onCamera = {
                cameraUri(context)?.let {
                    pendingCameraUri = it
                    camera.launch(it)
                }
            },
            onPerson = { personPicker.launch(arrayOf("image/*")) },
            onGarment = { garmentPicker.launch(arrayOf("image/*")) },
        )

        ProductCard(
            state = state,
            language = language,
            onUrlChange = viewModel::setProductUrl,
            onImport = viewModel::loadProduct,
            onUpload = { garmentPicker.launch(arrayOf("image/*")) },
        )

        SizePanel(state = state, language = language, onSize = viewModel::setGarmentSize)

        if (state.isGeneratingImage) {
            GenerationProgress(state = state, language = language)
        } else {
            Button(
                onClick = viewModel::generateImage,
                enabled = state.canGenerate && (state.productUrl.isBlank() || state.selectedGarmentSize != null),
                modifier = Modifier.fillMaxWidth().height(62.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(generateLabel(state, language), fontWeight = FontWeight.SemiBold)
            }
        }

        when (state.imageError) {
            GenerationError.API_KEY_MISSING -> ErrorCard(
                text = tr(language, "أكمل إعداد محرك الذكاء الاصطناعي أولًا.", "Finish AI engine setup first."),
                action = tr(language, "فتح AI", "Open AI"),
                onClick = onOpenAi,
            )
            GenerationError.REQUEST_FAILED -> ErrorCard(
                text = tr(language, "فشل التوليد. راجع المزوّد أو النموذج وحاول مرة أخرى.", "Generation failed. Review the provider or model and try again."),
                action = tr(language, "الإعدادات", "Settings"),
                onClick = onOpenAi,
            )
            GenerationError.NONE -> Unit
        }

        Text(
            tr(
                language,
                "إذا كان المقاس XS ضيقًا على جسمك، يجب أن تظهر النتيجة ضيقه — لا أن تغيّر جسمك ليصبح XS مناسبًا.",
                "If XS is tight on your body, the result should show that tightness — not reshape your body to make XS fit.",
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StudioHeader(language: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("ALMI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("FIT STUDIO / V8", style = MaterialTheme.typography.labelSmall, color = scheme.tertiary)
        }
        Surface(shape = RoundedCornerShape(999.dp), color = scheme.primary) {
            Row(
                Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).background(Color(0xFF6CF0B2), CircleShape))
                Text(tr(language, "جاهز", "READY"), color = scheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MediaStage(
    personImage: String?,
    garmentImage: String?,
    language: String,
    onCamera: () -> Unit,
    onPerson: () -> Unit,
    onGarment: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = scheme.primary,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaSlot(
                    label = tr(language, "جسمك", "YOU"),
                    image = personImage,
                    empty = tr(language, "أضف مرجع الجسم", "Add body reference"),
                    modifier = Modifier.weight(1f).height(365.dp),
                )
                MediaSlot(
                    label = tr(language, "القطعة", "GARMENT"),
                    image = garmentImage,
                    empty = tr(language, "أضف القطعة", "Add garment"),
                    modifier = Modifier.weight(.64f).height(365.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StageAction(Icons.Outlined.AddAPhoto, tr(language, "كاميرا", "Camera"), onCamera, Modifier.weight(1f))
                StageAction(Icons.Outlined.PhotoLibrary, tr(language, "صورتي", "Photo"), onPerson, Modifier.weight(1f))
                StageAction(Icons.Outlined.Checkroom, tr(language, "قطعة", "Garment"), onGarment, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MediaSlot(label: String, image: String?, empty: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(25.dp), color = Color(0xFF20242B)) {
        Box(Modifier.fillMaxSize()) {
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White.copy(alpha = .48f), modifier = Modifier.size(27.dp))
                    Text(empty, color = Color.White.copy(alpha = .58f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.Black.copy(alpha = .46f),
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StageAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = .10f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .11f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ProductCard(
    state: TryOnUiState,
    language: String,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onUpload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(tr(language, "استيراد من المتجر", "Import from store"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = state.productUrl,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(tr(language, "الصق رابط المنتج", "Paste product URL")) },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = !state.isLoadingProduct, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                    if (state.isLoadingProduct) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Link, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr(language, "استخراج", "Import"))
                }
                OutlinedButton(onClick = onUpload, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr(language, "رفع صورة", "Upload"))
                }
            }
            if (state.productTitle.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.productTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        if (state.merchant.isNotBlank()) Text(state.merchant, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.displayProductPrice.isNotBlank()) Text(state.displayProductPrice, color = scheme.tertiary, style = MaterialTheme.typography.labelLarge)
                }
            }
            when (state.productError) {
                ProductError.EMPTY_URL -> ErrorLine(tr(language, "أدخل رابطًا أولًا.", "Enter a URL first."))
                ProductError.UNAVAILABLE -> ErrorLine(tr(language, "تعذر قراءة الرابط.", "The URL could not be read."))
                ProductError.IMAGE_NOT_FOUND -> ErrorLine(tr(language, "تمت قراءة المنتج لكن لم نجد صورة مناسبة.", "Product loaded but no suitable image was found."))
                ProductError.NONE -> Unit
            }
        }
    }
}

@Composable
private fun SizePanel(state: TryOnUiState, language: String, onSize: (GarmentSize) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column {
                    Text(tr(language, "المقاس", "Size"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(tr(language, "اختر نفس المقاس الموجود في المتجر.", "Choose the exact store size."), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text(state.selectedGarmentSize?.label ?: "—", color = scheme.tertiary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.availableGarmentSizes.forEach { size ->
                    SizeChip(size = size, selected = state.selectedGarmentSize == size, onClick = { onSize(size) })
                }
            }
            state.fitSimulation?.let { FitSummary(it, language) }
        }
    }
}

@Composable
private fun SizeChip(size: GarmentSize, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) scheme.primary else scheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Text(
            size.label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            color = if (selected) scheme.onPrimary else scheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FitSummary(fit: FitSimulation, language: String) {
    val scheme = MaterialTheme.colorScheme
    val pressure = when (fit.overallPressure) {
        FitPressure.VERY_TIGHT -> tr(language, "شديد الضيق", "Very tight")
        FitPressure.TIGHT -> tr(language, "ضيق", "Tight")
        FitPressure.CLOSE -> tr(language, "ملاصق", "Close")
        FitPressure.REGULAR -> tr(language, "اعتيادي", "Regular")
        FitPressure.LOOSE -> tr(language, "واسع", "Loose")
        FitPressure.UNKNOWN -> tr(language, "تقديري", "Approximate")
    }
    val confidence = when (fit.confidence) {
        FitConfidence.HIGH -> tr(language, "ثقة مرتفعة", "High confidence")
        FitConfidence.MEDIUM -> tr(language, "ثقة متوسطة", "Medium confidence")
        FitConfidence.LOW -> tr(language, "بدون جدول مقاسات موثوق", "No reliable size chart")
    }
    Surface(shape = RoundedCornerShape(18.dp), color = scheme.tertiaryContainer.copy(alpha = .72f)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("${fit.size.label}  ·  $pressure", fontWeight = FontWeight.SemiBold)
                Text(confidence, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (fit.confidence == FitConfidence.LOW) {
                    tr(language, "حرف المقاس وحده ليس معيارًا عالميًا؛ النتيجة تبقى تقريبية حتى يتوفر جدول المتجر.", "Letter sizes are not universal; fit stays approximate until a retailer chart is available.")
                } else {
                    tr(language, "تمت مقارنة بيانات المقاس المتاحة بقياسات جسمك.", "Available size data was compared with your body measurements.")
                },
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GenerationProgress(state: TryOnUiState, language: String) {
    val p = state.imageProgress.coerceIn(0f, 1f)
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(tr(language, "نبني الإطلالة", "Building your fit"), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                Text("${(p * 100).toInt()}%", color = MaterialTheme.colorScheme.onPrimary)
            }
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Color.White, trackColor = Color.White.copy(alpha = .16f))
        }
    }
}

@Composable
private fun ResultScreen(
    state: TryOnUiState,
    language: String,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onOpenAi: () -> Unit,
    onMotion: (MotionDirection) -> Unit,
    onVideo: () -> Unit,
) {
    val generated = state.generatedImage ?: return
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("ALMI / RESULT", color = scheme.tertiary, style = MaterialTheme.typography.labelSmall)
                Text(
                    state.selectedGarmentSize?.let { tr(language, "نتيجة ${it.label}", "Size ${it.label} result") } ?: tr(language, "النتيجة", "Your result"),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = null) }
                IconButton(onClick = onOpenAi) { Icon(Icons.Outlined.Tune, contentDescription = null) }
                IconButton(onClick = onReset) { Icon(Icons.Outlined.Refresh, contentDescription = null) }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = scheme.primary,
            shadowElevation = 8.dp,
        ) {
            AsyncImage(model = generated, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(.73f), contentScale = ContentScale.Crop)
        }

        state.fitSimulation?.let { FitSummary(it, language) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text(tr(language, "حوّلها إلى فيديو", "Turn it into a video"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(tr(language, "اختر حركة بسيطة وثابتة.", "Choose one stable motion."), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Outlined.SmartDisplay, contentDescription = null, tint = scheme.tertiary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MotionChip(MotionDirection.TURN, state.motion, tr(language, "دوران", "Turn"), onMotion, Modifier.weight(1f))
                    MotionChip(MotionDirection.WALK, state.motion, tr(language, "مشي", "Walk"), onMotion, Modifier.weight(1f))
                    MotionChip(MotionDirection.DETAIL, state.motion, tr(language, "تفاصيل", "Detail"), onMotion, Modifier.weight(1f))
                }
                if (state.generatedVideo == null) {
                    Button(onClick = onVideo, enabled = !state.isGeneratingVideo, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                        if (state.isGeneratingVideo) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.SmartDisplay, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text(videoLabel(state, language))
                    }
                } else {
                    AndroidView(
                        factory = { ctx -> VideoView(ctx).apply { setOnPreparedListener { media -> media.isLooping = true; start() } } },
                        update = { view ->
                            if (view.tag != state.generatedVideo) {
                                view.tag = state.generatedVideo
                                view.setVideoURI(Uri.parse(state.generatedVideo))
                                view.start()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(20.dp)),
                    )
                }
                if (state.videoError) ErrorLine(tr(language, "تعذر إنشاء الفيديو. راجع إعدادات المزوّد.", "Video generation failed. Review provider settings."))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MotionChip(
    value: MotionDirection,
    current: MotionDirection,
    label: String,
    onSelect: (MotionDirection) -> Unit,
    modifier: Modifier,
) {
    val selected = value == current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable { onSelect(value) },
        shape = RoundedCornerShape(15.dp),
        color = if (selected) scheme.primary else scheme.surfaceVariant,
    ) {
        Text(label, modifier = Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center, color = if (selected) scheme.onPrimary else scheme.onSurface, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ErrorCard(text: String, action: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = scheme.errorContainer) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, modifier = Modifier.weight(1f), color = scheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun generateLabel(state: TryOnUiState, language: String): String = when {
    state.personImage == null -> tr(language, "أضف مرجع الجسم", "Add body reference")
    state.effectiveGarmentImage == null -> tr(language, "أضف القطعة", "Add garment")
    state.productUrl.isNotBlank() && state.selectedGarmentSize == null -> tr(language, "اختر المقاس", "Choose a size")
    else -> tr(language, "محاكاة المقاس", "Simulate fit")
}

private fun videoLabel(state: TryOnUiState, language: String): String = when (state.videoStatus) {
    VideoGenerationStatus.IDLE -> tr(language, "إنشاء فيديو", "Create video")
    VideoGenerationStatus.SUBMITTING -> tr(language, "إرسال الطلب…", "Submitting…")
    VideoGenerationStatus.PROCESSING -> tr(language, "معالجة الفيديو…", "Processing…")
    VideoGenerationStatus.DOWNLOADING -> tr(language, "تجهيز الفيديو…", "Preparing…")
}

private fun cameraUri(context: Context): Uri? = runCatching {
    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(directory, "almi_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

private fun persistPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun tr(language: String, ar: String, en: String): String = if (language == "ar") ar else en
