package com.almi.ai.ui.v12

import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.almi.ai.data.preferences.AvatarPresentation
import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import com.almi.ai.data.preferences.guidedMeasurementOrder
import java.util.Locale

private const val INCH_TO_CM = 2.54f
private const val POUND_TO_KG = 0.45359237f

private data class BodyMarkerSpec(val anchor: String, val measurement: BodyMeasurePoint)

private val v12BodyMarkers = listOf(
    BodyMarkerSpec("neck", BodyMeasurePoint.NECK),
    BodyMarkerSpec("shoulderCenter", BodyMeasurePoint.SHOULDERS),
    BodyMarkerSpec("rightShoulder", BodyMeasurePoint.SHOULDER_LENGTH),
    BodyMarkerSpec("chest", BodyMeasurePoint.CHEST),
    BodyMarkerSpec("underbust", BodyMeasurePoint.UNDERBUST),
    BodyMarkerSpec("leftBust", BodyMeasurePoint.BUST_HEIGHT),
    BodyMarkerSpec("rightBust", BodyMeasurePoint.BUST_POINT_DISTANCE),
    BodyMarkerSpec("waist", BodyMeasurePoint.WAIST),
    BodyMarkerSpec("abdomen", BodyMeasurePoint.ABDOMEN),
    BodyMarkerSpec("hips", BodyMeasurePoint.HIPS),
    BodyMarkerSpec("leftShoulder", BodyMeasurePoint.DRESS_LENGTH),
    BodyMarkerSpec("rightElbow", BodyMeasurePoint.ARM_LENGTH),
    BodyMarkerSpec("rightUpperArm", BodyMeasurePoint.UPPER_ARM),
    BodyMarkerSpec("rightHand", BodyMeasurePoint.WRIST),
)

@Composable
internal fun V12BodyMapScreen(
    language: String,
    profile: BodyProfile,
    presentation: AvatarPresentation,
    onPresentation: (AvatarPresentation) -> Unit,
    onHeightChanged: (Float) -> Unit,
    onWeightChanged: (Float) -> Unit,
    onMeasurementChanged: (BodyMeasurePoint, Float) -> Unit,
    onMeasurementCleared: (BodyMeasurePoint) -> Unit,
    onBack: () -> Unit,
) {
    val palette = V12Palettes.Body
    var rendererState by remember(presentation) { mutableStateOf(V12BodyRendererState.LOADING) }
    var projection by remember(presentation) { mutableStateOf<V12BodyProjection?>(null) }
    var runtime by remember(presentation) { mutableStateOf<V12BodyRuntime?>(null) }
    var selected by rememberSaveable {
        mutableStateOf(profile.nextRecommendedMeasurement ?: BodyMeasurePoint.CHEST)
    }
    val currentInches = profile.measurementsInches[selected]
    var editorText by remember(selected, currentInches) {
        mutableStateOf(currentInches?.let { formatOne(it * INCH_TO_CM) } ?: "")
    }

    LaunchedEffect(presentation) {
        rendererState = V12BodyRendererState.LOADING
        projection = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1D1B19))
            .statusBarsPadding(),
    ) {
        key(presentation) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).also { surface ->
                        runtime = V12BodyRuntime(
                            context = context,
                            surfaceView = surface,
                            presentation = presentation,
                            onStateChanged = { state -> surface.post { rendererState = state } },
                            onProjectionChanged = { value -> surface.post { projection = value } },
                        ).also {
                            it.initialize()
                            it.start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        DisposableEffect(presentation) {
            onDispose { runtime?.stop() }
        }

        MeasurementGuide(
            selected = selected,
            projection = projection,
            signal = palette.signal,
        )

        BodyMarkers(
            selected = selected,
            projection = projection,
            signal = palette.signal,
            onSelect = { point, screenY ->
                selected = point
                runtime?.focusOn(screenY)
            },
        )

        BodyMapHeader(language, palette, onBack)

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 78.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            GenderChip(
                label = if (language == "ar") "أنثى" else "F",
                selected = presentation == AvatarPresentation.FEMININE,
                palette = palette,
                onClick = { onPresentation(AvatarPresentation.FEMININE) },
            )
            GenderChip(
                label = if (language == "ar") "ذكر" else "M",
                selected = presentation == AvatarPresentation.MASCULINE,
                palette = palette,
                onClick = { onPresentation(AvatarPresentation.MASCULINE) },
            )
        }

        RendererStatus(rendererState, language, palette)

        MeasurementRail(
            language = language,
            selected = selected,
            completed = profile.completedMeasurements,
            total = guidedMeasurementOrder.size,
            palette = palette,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            onPrevious = {
                val next = previousMeasurement(selected)
                selected = next
                focusSelected(runtime, projection, next)
            },
            onNext = {
                val next = nextMeasurement(selected)
                selected = next
                focusSelected(runtime, projection, next)
            },
            onResetView = { runtime?.resetView() },
        )

        MeasurementEditor(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 14.dp),
            language = language,
            selected = selected,
            currentInches = currentInches,
            editorText = editorText,
            palette = palette,
            onTextChange = { raw ->
                val filtered = raw.filter { it.isDigit() || it == '.' || it == ',' }.take(6)
                editorText = filtered
                filtered.replace(',', '.').toFloatOrNull()?.let { cm ->
                    if (cm in 5f..250f) onMeasurementChanged(selected, cm / INCH_TO_CM)
                }
            },
            onClear = {
                editorText = ""
                onMeasurementCleared(selected)
            },
        )

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            BodyMetricStepper(
                label = if (language == "ar") "الطول" else "HEIGHT",
                value = "${formatZero(profile.heightCentimeters)} CM",
                onMinus = {
                    onHeightChanged(((profile.heightCentimeters - 1f).coerceAtLeast(120f)) / INCH_TO_CM)
                },
                onPlus = {
                    onHeightChanged(((profile.heightCentimeters + 1f).coerceAtMost(220f)) / INCH_TO_CM)
                },
            )
            BodyMetricStepper(
                label = if (language == "ar") "الوزن" else "WEIGHT",
                value = "${formatZero(profile.weightKilograms)} KG",
                onMinus = {
                    onWeightChanged(((profile.weightKilograms - 1f).coerceAtLeast(35f)) / POUND_TO_KG)
                },
                onPlus = {
                    onWeightChanged(((profile.weightKilograms + 1f).coerceAtMost(220f)) / POUND_TO_KG)
                },
            )
        }
    }
}

@Composable
private fun MeasurementGuide(
    selected: BodyMeasurePoint,
    projection: V12BodyProjection?,
    signal: Color,
) {
    Canvas(Modifier.fillMaxSize()) {
        val points = projection?.points ?: return@Canvas

        fun screen(name: String): Offset? {
            val point = points[name] ?: return null
            if (!point.visible) return null
            return Offset(point.x * size.width, point.y * size.height)
        }

        fun line(a: String, b: String) {
            val start = screen(a) ?: return
            val end = screen(b) ?: return
            drawLine(signal.copy(alpha = .92f), start, end, 2.2f)
            drawCircle(Color.White.copy(alpha = .85f), 4.2f, start)
            drawCircle(Color.White.copy(alpha = .85f), 4.2f, end)
        }

        fun oval(anchor: String, widthFraction: Float, heightFraction: Float) {
            val center = screen(anchor) ?: return
            val width = size.width * widthFraction
            val height = size.height * heightFraction
            drawOval(
                color = signal.copy(alpha = .92f),
                topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                size = Size(width, height),
                style = Stroke(2.2f),
            )
            drawOval(
                color = Color.White.copy(alpha = .58f),
                topLeft = Offset(center.x - width / 2f - 2f, center.y - height / 2f - 2f),
                size = Size(width + 4f, height + 4f),
                style = Stroke(.7f),
            )
        }

        when (selected) {
            BodyMeasurePoint.NECK -> oval("neck", .17f, .020f)
            BodyMeasurePoint.SHOULDERS -> line("leftShoulder", "rightShoulder")
            BodyMeasurePoint.SHOULDER_LENGTH -> line("shoulderCenter", "rightShoulder")
            BodyMeasurePoint.CHEST -> oval("chest", .36f, .025f)
            BodyMeasurePoint.UNDERBUST -> oval("underbust", .33f, .023f)
            BodyMeasurePoint.BUST_HEIGHT -> line("leftShoulder", "leftBust")
            BodyMeasurePoint.BUST_POINT_DISTANCE -> line("leftBust", "rightBust")
            BodyMeasurePoint.WAIST -> oval("waist", .30f, .023f)
            BodyMeasurePoint.ABDOMEN -> oval("abdomen", .33f, .024f)
            BodyMeasurePoint.HIPS -> oval("hips", .38f, .026f)
            BodyMeasurePoint.DRESS_LENGTH -> line("leftShoulder", "leftThigh")
            BodyMeasurePoint.ARM_LENGTH -> line("rightShoulder", "rightHand")
            BodyMeasurePoint.UPPER_ARM -> oval("rightUpperArm", .13f, .018f)
            BodyMeasurePoint.WRIST -> oval("rightHand", .09f, .015f)
            else -> Unit
        }
    }
}

@Composable
private fun BodyMarkers(
    selected: BodyMeasurePoint,
    projection: V12BodyProjection?,
    signal: Color,
    onSelect: (BodyMeasurePoint, Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val points = projection?.points.orEmpty()
        v12BodyMarkers.forEach { spec ->
            val point = points[spec.anchor]
            if (point != null && point.visible) {
                val active = spec.measurement == selected
                val markerSize = if (active) 20.dp else 14.dp
                Surface(
                    modifier = Modifier
                        .offset(
                            x = maxWidth * point.x.coerceIn(0f, 1f) - markerSize / 2,
                            y = maxHeight * point.y.coerceIn(0f, 1f) - markerSize / 2,
                        )
                        .size(markerSize)
                        .clickable { onSelect(spec.measurement, point.y) },
                    shape = CircleShape,
                    color = signal.copy(alpha = if (active) 1f else .76f),
                    border = BorderStroke(if (active) 3.dp else 2.dp, Color.White.copy(alpha = .92f)),
                    shadowElevation = if (active) 6.dp else 1.dp,
                ) {}
            }
        }
    }
}

@Composable
private fun BodyMapHeader(language: String, palette: V12Palette, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "ALMI / BODY MAP 12",
                color = Color.White.copy(alpha = .58f),
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.25.sp,
            )
            Text(
                if (language == "ar") "خريطة الجسم" else "BODY MAP",
                color = Color.White,
                fontSize = 29.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-.8).sp,
            )
            Text(
                if (language == "ar") "مجسم واقعي • نقاط من الهيكل الفعلي" else "REAL SKIN • RIG-SOLVED LANDMARKS",
                color = Color.White.copy(alpha = .48f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .9.sp,
            )
        }
        V12BackControl(
            palette = V12Palette(
                background = Color.Transparent,
                ink = Color.White,
                muted = Color.White.copy(alpha = .55f),
                panel = Color.Black.copy(alpha = .26f),
                edge = Color.White.copy(alpha = .20f),
                signal = palette.signal,
                signalInk = Color.White,
            ),
            label = if (language == "ar") "العوالم" else "WORLDS",
            onBack = onBack,
        )
    }
}

@Composable
private fun GenderChip(
    label: String,
    selected: Boolean,
    palette: V12Palette,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) palette.signal else Color.Black.copy(alpha = .32f),
        border = BorderStroke(1.dp, if (selected) palette.signal else Color.White.copy(alpha = .22f)),
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun RendererStatus(
    state: V12BodyRendererState,
    language: String,
    palette: V12Palette,
) {
    when (state) {
        V12BodyRendererState.READY -> Unit
        V12BodyRendererState.LOADING -> Surface(
            modifier = Modifier,
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = .44f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = palette.signal,
                    strokeWidth = 2.dp,
                )
                Text(
                    if (language == "ar") "تحميل الجسم الواقعي" else "BUILDING REAL BODY",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = .9.sp,
                )
            }
        }
        V12BodyRendererState.ERROR -> Surface(
            modifier = Modifier,
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF2A1718),
            border = BorderStroke(1.dp, palette.signal.copy(alpha = .55f)),
        ) {
            Text(
                if (language == "ar") "تعذر تحميل المجسم" else "BODY RENDER FAILED",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun MeasurementRail(
    language: String,
    selected: BodyMeasurePoint,
    completed: Int,
    total: Int,
    palette: V12Palette,
    modifier: Modifier,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onResetView: () -> Unit,
) {
    Surface(
        modifier = modifier.width(54.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color.Black.copy(alpha = .34f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .16f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RailButton("↑", onPrevious)
            Text(measurementNumber(selected).removePrefix("0"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text("$completed/$total", color = Color.White.copy(alpha = .48f), fontSize = 7.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.width(24.dp).height(1.dp).background(Color.White.copy(alpha = .16f)))
            RailButton("↓", onNext)
            Surface(
                modifier = Modifier.clickable(onClick = onResetView),
                shape = CircleShape,
                color = palette.signal.copy(alpha = .92f),
            ) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    V12Glyph(V12GlyphType.RESET, Color.White, Modifier.size(17.dp))
                }
            }
            Text(
                if (language == "ar") "قياس" else "MEASURE",
                color = Color.White.copy(alpha = .42f),
                fontSize = 6.5.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun RailButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.White.copy(alpha = .10f),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun MeasurementEditor(
    modifier: Modifier,
    language: String,
    selected: BodyMeasurePoint,
    currentInches: Float?,
    editorText: String,
    palette: V12Palette,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(min = 228.dp, max = 282.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 10.dp, bottomEnd = 30.dp, bottomStart = 10.dp),
        color = Color(0xEEFAF8F5),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .55f)),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(measurementNumber(selected), color = palette.signal, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text(measurementLabel(selected, language), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Black)
                }
                if (currentInches != null) {
                    Text(
                        if (language == "ar") "محفوظ" else "SAVED",
                        color = palette.muted,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = .8.sp,
                    )
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(
                    value = editorText,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = TextStyle(color = palette.ink, fontSize = 27.sp, fontWeight = FontWeight.Black),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(palette.signal),
                    modifier = Modifier.width(96.dp),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .background(Color(0xFFF0ECE6), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            if (editorText.isBlank()) {
                                Text("—", color = palette.muted, fontSize = 27.sp, fontWeight = FontWeight.Black)
                            }
                            inner()
                        }
                    },
                )
                Text("CM", color = palette.muted, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 10.dp))
                Spacer(Modifier.weight(1f))
                if (currentInches != null) {
                    Surface(
                        modifier = Modifier.clickable(onClick = onClear),
                        shape = RoundedCornerShape(999.dp),
                        color = palette.signal.copy(alpha = .08f),
                    ) {
                        Text(
                            if (language == "ar") "مسح" else "CLEAR",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            color = palette.signal,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyMetricStepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = .42f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .15f)),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MetricButton("−", onMinus)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = Color.White.copy(alpha = .48f), fontSize = 6.5.sp, fontWeight = FontWeight.Black, letterSpacing = .6.sp)
                Text(value, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
            MetricButton("+", onPlus)
        }
    }
}

@Composable
private fun MetricButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.White.copy(alpha = .11f),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun focusSelected(runtime: V12BodyRuntime?, projection: V12BodyProjection?, selected: BodyMeasurePoint) {
    val y = projection?.points?.get(anchorFor(selected))?.y ?: return
    runtime?.focusOn(y)
}

private fun anchorFor(point: BodyMeasurePoint): String = when (point) {
    BodyMeasurePoint.NECK -> "neck"
    BodyMeasurePoint.SHOULDERS -> "shoulderCenter"
    BodyMeasurePoint.SHOULDER_LENGTH -> "rightShoulder"
    BodyMeasurePoint.CHEST -> "chest"
    BodyMeasurePoint.UNDERBUST -> "underbust"
    BodyMeasurePoint.BUST_HEIGHT -> "leftBust"
    BodyMeasurePoint.BUST_POINT_DISTANCE -> "rightBust"
    BodyMeasurePoint.WAIST -> "waist"
    BodyMeasurePoint.ABDOMEN -> "abdomen"
    BodyMeasurePoint.HIPS -> "hips"
    BodyMeasurePoint.DRESS_LENGTH -> "leftShoulder"
    BodyMeasurePoint.ARM_LENGTH -> "rightHand"
    BodyMeasurePoint.UPPER_ARM -> "rightUpperArm"
    BodyMeasurePoint.WRIST -> "rightHand"
    else -> "pelvis"
}

private fun previousMeasurement(current: BodyMeasurePoint): BodyMeasurePoint {
    val index = guidedMeasurementOrder.indexOf(current).coerceAtLeast(0)
    return guidedMeasurementOrder[(index - 1 + guidedMeasurementOrder.size) % guidedMeasurementOrder.size]
}

private fun nextMeasurement(current: BodyMeasurePoint): BodyMeasurePoint {
    val index = guidedMeasurementOrder.indexOf(current).coerceAtLeast(0)
    return guidedMeasurementOrder[(index + 1) % guidedMeasurementOrder.size]
}

private fun measurementNumber(point: BodyMeasurePoint): String {
    val index = guidedMeasurementOrder.indexOf(point).coerceAtLeast(0) + 1
    return index.toString().padStart(2, '0')
}

private fun measurementLabel(point: BodyMeasurePoint, language: String): String {
    val ar = language == "ar"
    return when (point) {
        BodyMeasurePoint.NECK -> if (ar) "محيط الرقبة" else "NECK"
        BodyMeasurePoint.SHOULDERS -> if (ar) "عرض الكتفين" else "SHOULDERS"
        BodyMeasurePoint.SHOULDER_LENGTH -> if (ar) "طول الكتف" else "SHOULDER LENGTH"
        BodyMeasurePoint.CHEST -> if (ar) "محيط الصدر" else "CHEST"
        BodyMeasurePoint.UNDERBUST -> if (ar) "أسفل الصدر" else "UNDERBUST"
        BodyMeasurePoint.BUST_HEIGHT -> if (ar) "ارتفاع الصدر" else "BUST HEIGHT"
        BodyMeasurePoint.BUST_POINT_DISTANCE -> if (ar) "المسافة بين نقطتي الصدر" else "BUST POINT DISTANCE"
        BodyMeasurePoint.WAIST -> if (ar) "محيط الخصر" else "WAIST"
        BodyMeasurePoint.ABDOMEN -> if (ar) "محيط البطن" else "ABDOMEN"
        BodyMeasurePoint.HIPS -> if (ar) "محيط الأرداف" else "HIPS"
        BodyMeasurePoint.DRESS_LENGTH -> if (ar) "طول الفستان" else "DRESS LENGTH"
        BodyMeasurePoint.ARM_LENGTH -> if (ar) "طول الذراع" else "ARM LENGTH"
        BodyMeasurePoint.UPPER_ARM -> if (ar) "محيط أعلى الذراع" else "UPPER ARM"
        BodyMeasurePoint.WRIST -> if (ar) "محيط المعصم" else "WRIST"
        else -> point.key.uppercase(Locale.US)
    }
}

private fun formatOne(value: Float): String = String.format(Locale.US, "%.1f", value)
private fun formatZero(value: Float): String = String.format(Locale.US, "%.0f", value)
