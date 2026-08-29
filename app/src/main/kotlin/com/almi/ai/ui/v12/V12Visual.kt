package com.almi.ai.ui.v12

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class V12World { INDEX, FIT, AVATAR, BODY, AI, CONTROL }

internal data class V12Palette(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val panel: Color,
    val edge: Color,
    val signal: Color,
    val signalInk: Color,
)

/**
 * v12 visual direction: luminous, airy and editorial.
 * No world uses a black/dark canvas by default. Dark navy is reserved for readable typography only.
 */
internal object V12Palettes {
    val Index = V12Palette(
        background = Color(0xFFF1FAFF),
        ink = Color(0xFF18324A),
        muted = Color(0xFF6F8CA3),
        panel = Color(0xFFFFFFFF),
        edge = Color(0xFFCFE6F4),
        signal = Color(0xFF65B9EF),
        signalInk = Color(0xFFFFFFFF),
    )
    val Fit = V12Palette(
        background = Color(0xFFF0FAFF),
        ink = Color(0xFF17324B),
        muted = Color(0xFF708DA4),
        panel = Color(0xFFFFFFFF),
        edge = Color(0xFFCBE7F6),
        signal = Color(0xFF4DAFEA),
        signalInk = Color(0xFFFFFFFF),
    )
    val Avatar = V12Palette(
        background = Color(0xFFF3FAFF),
        ink = Color(0xFF203A52),
        muted = Color(0xFF7892A8),
        panel = Color(0xFFFFFFFF),
        edge = Color(0xFFD2E8F4),
        signal = Color(0xFF62BCEF),
        signalInk = Color(0xFFFFFFFF),
    )
    val Body = V12Palette(
        background = Color(0xFFEFF9FF),
        ink = Color(0xFF17344D),
        muted = Color(0xFF6E8BA1),
        panel = Color(0xFFFFFFFF),
        edge = Color(0xFFC9E5F4),
        signal = Color(0xFF58B3EA),
        signalInk = Color(0xFFFFFFFF),
    )
    val Ai = V12Palette(
        background = Color(0xFFF1FCFA),
        ink = Color(0xFF1B4050),
        muted = Color(0xFF6F9297),
        panel = Color(0xFFFFFFFF),
        edge = Color(0xFFCDE9E5),
        signal = Color(0xFF55CDB8),
        signalInk = Color(0xFFFFFFFF),
    )
    val Control = V12Palette(
        background = Color(0xFFF7FAFF),
        ink = Color(0xFF20384E),
        muted = Color(0xFF7A8EA2),
        panel = Color(0xFFFFFFFF),
        edge = Color(0xFFD6E5F0),
        signal = Color(0xFF8BAEF5),
        signalInk = Color(0xFFFFFFFF),
    )
}

internal enum class V12GlyphType {
    FIT, AVATAR, BODY, AI, CONTROL, BACK, CAMERA, IMAGE, LINK, SIZE,
    TURN, WALK, DETAIL, RESET, LANGUAGE, THEME,
}

/** Hand-drawn ALMI glyph set. Deliberately avoids Material icon silhouettes. */
@Composable
internal fun V12Glyph(
    type: V12GlyphType,
    tint: Color,
    modifier: Modifier = Modifier,
    stroke: Float = 1.8f,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = minOf(w, h)
        val sw = stroke * density

        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, sw, StrokeCap.Round)
        fun circle(c: Offset, r: Float, fill: Boolean = false) {
            if (fill) drawCircle(tint, r, c) else drawCircle(tint, r, c, style = Stroke(sw))
        }
        fun arc(
            start: Float,
            sweep: Float,
            useCenter: Boolean,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            outlined: Boolean = true,
        ) {
            drawArc(
                color = tint,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = useCenter,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = if (outlined) Stroke(sw, cap = StrokeCap.Round) else androidx.compose.ui.graphics.drawscope.Fill,
            )
        }
        fun oval(left: Float, top: Float, right: Float, bottom: Float) {
            drawOval(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(sw),
            )
        }

        when (type) {
            V12GlyphType.FIT -> {
                val top = h * .22f
                line(Offset(w * .28f, top), Offset(w * .5f, h * .34f))
                line(Offset(w * .72f, top), Offset(w * .5f, h * .34f))
                line(Offset(w * .5f, h * .34f), Offset(w * .5f, h * .47f))
                val path = Path().apply {
                    moveTo(w * .5f, h * .46f)
                    lineTo(w * .18f, h * .68f)
                    quadraticBezierTo(w * .12f, h * .75f, w * .25f, h * .78f)
                    lineTo(w * .75f, h * .78f)
                    quadraticBezierTo(w * .88f, h * .75f, w * .82f, h * .68f)
                    close()
                }
                drawPath(path, tint, style = Stroke(sw, cap = StrokeCap.Round))
            }

            V12GlyphType.AVATAR -> {
                circle(Offset(w * .5f, h * .30f), s * .13f)
                arc(200f, 140f, false, w * .25f, h * .47f, w * .75f, h * .88f)
                line(Offset(w * .39f, h * .52f), Offset(w * .34f, h * .82f))
                line(Offset(w * .61f, h * .52f), Offset(w * .66f, h * .82f))
            }

            V12GlyphType.BODY -> {
                circle(Offset(w * .5f, h * .18f), s * .09f)
                line(Offset(w * .5f, h * .28f), Offset(w * .5f, h * .64f))
                line(Offset(w * .5f, h * .38f), Offset(w * .25f, h * .50f))
                line(Offset(w * .5f, h * .38f), Offset(w * .75f, h * .50f))
                line(Offset(w * .5f, h * .64f), Offset(w * .34f, h * .88f))
                line(Offset(w * .5f, h * .64f), Offset(w * .66f, h * .88f))
                drawLine(
                    tint,
                    Offset(w * .18f, h * .54f),
                    Offset(w * .82f, h * .54f),
                    sw * .7f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(sw * 2.5f, sw * 2.5f)),
                )
            }

            V12GlyphType.AI -> {
                val c = Offset(w * .5f, h * .5f)
                circle(c, s * .14f, true)
                line(Offset(c.x, h * .12f), Offset(c.x, h * .28f))
                line(Offset(c.x, h * .72f), Offset(c.x, h * .88f))
                line(Offset(w * .12f, c.y), Offset(w * .28f, c.y))
                line(Offset(w * .72f, c.y), Offset(w * .88f, c.y))
                line(Offset(w * .23f, h * .23f), Offset(w * .34f, h * .34f))
                line(Offset(w * .66f, h * .66f), Offset(w * .77f, h * .77f))
                line(Offset(w * .77f, h * .23f), Offset(w * .66f, h * .34f))
                line(Offset(w * .34f, h * .66f), Offset(w * .23f, h * .77f))
            }

            V12GlyphType.CONTROL -> {
                listOf(.28f, .50f, .72f).forEachIndexed { index, y ->
                    line(Offset(w * .18f, h * y), Offset(w * .82f, h * y))
                    val x = when (index) { 0 -> .36f; 1 -> .67f; else -> .48f }
                    circle(Offset(w * x, h * y), s * .07f, true)
                }
            }

            V12GlyphType.BACK -> {
                line(Offset(w * .70f, h * .22f), Offset(w * .34f, h * .50f))
                line(Offset(w * .34f, h * .50f), Offset(w * .70f, h * .78f))
            }

            V12GlyphType.CAMERA -> {
                drawRoundRect(
                    tint,
                    Offset(w * .15f, h * .30f),
                    Size(w * .70f, h * .52f),
                    CornerRadius(s * .08f),
                    style = Stroke(sw),
                )
                circle(Offset(w * .50f, h * .56f), s * .15f)
                line(Offset(w * .31f, h * .30f), Offset(w * .39f, h * .20f))
                line(Offset(w * .39f, h * .20f), Offset(w * .59f, h * .20f))
            }

            V12GlyphType.IMAGE -> {
                drawRoundRect(
                    tint,
                    Offset(w * .16f, h * .16f),
                    Size(w * .68f, h * .68f),
                    CornerRadius(s * .07f),
                    style = Stroke(sw),
                )
                circle(Offset(w * .63f, h * .36f), s * .07f)
                line(Offset(w * .20f, h * .72f), Offset(w * .40f, h * .50f))
                line(Offset(w * .40f, h * .50f), Offset(w * .55f, h * .63f))
                line(Offset(w * .55f, h * .63f), Offset(w * .72f, h * .46f))
            }

            V12GlyphType.LINK -> {
                arc(130f, 220f, false, w * .12f, h * .20f, w * .58f, h * .72f)
                arc(-50f, 220f, false, w * .42f, h * .28f, w * .88f, h * .80f)
                line(Offset(w * .38f, h * .61f), Offset(w * .62f, h * .39f))
            }

            V12GlyphType.SIZE -> {
                line(Offset(w * .16f, h * .66f), Offset(w * .84f, h * .34f))
                repeat(5) { i ->
                    val t = i / 4f
                    val x = w * (.20f + .56f * t)
                    val y = h * (.64f - .26f * t)
                    line(Offset(x, y), Offset(x - w * .05f, y - h * .10f))
                }
            }

            V12GlyphType.TURN -> {
                arc(35f, 280f, false, w * .20f, h * .20f, w * .80f, h * .80f)
                line(Offset(w * .72f, h * .18f), Offset(w * .82f, h * .28f))
                line(Offset(w * .82f, h * .28f), Offset(w * .69f, h * .31f))
            }

            V12GlyphType.WALK -> {
                circle(Offset(w * .55f, h * .20f), s * .08f)
                line(Offset(w * .50f, h * .30f), Offset(w * .43f, h * .58f))
                line(Offset(w * .43f, h * .58f), Offset(w * .25f, h * .82f))
                line(Offset(w * .43f, h * .58f), Offset(w * .68f, h * .80f))
                line(Offset(w * .47f, h * .40f), Offset(w * .73f, h * .46f))
                line(Offset(w * .47f, h * .40f), Offset(w * .29f, h * .52f))
            }

            V12GlyphType.DETAIL -> {
                circle(Offset(w * .43f, h * .43f), s * .24f)
                line(Offset(w * .60f, h * .60f), Offset(w * .82f, h * .82f))
            }

            V12GlyphType.RESET -> {
                arc(40f, 285f, false, w * .18f, h * .18f, w * .82f, h * .82f)
                line(Offset(w * .72f, h * .16f), Offset(w * .84f, h * .28f))
                line(Offset(w * .84f, h * .28f), Offset(w * .68f, h * .31f))
            }

            V12GlyphType.LANGUAGE -> {
                circle(Offset(w * .5f, h * .5f), s * .31f)
                line(Offset(w * .19f, h * .5f), Offset(w * .81f, h * .5f))
                oval(w * .37f, h * .19f, w * .63f, h * .81f)
            }

            V12GlyphType.THEME -> {
                circle(Offset(w * .5f, h * .5f), s * .30f)
                arc(-90f, 180f, true, w * .20f, h * .20f, w * .80f, h * .80f, outlined = false)
            }
        }
    }
}

@Composable
internal fun V12BackControl(
    palette: V12Palette,
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onBack),
        shape = RoundedCornerShape(999.dp),
        color = palette.panel.copy(alpha = .92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V12Glyph(V12GlyphType.BACK, palette.ink, Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = palette.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun V12SignalButton(
    text: String,
    palette: V12Palette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: V12GlyphType? = null,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = palette.signal,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                V12Glyph(glyph, palette.signalInk, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = palette.signalInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = .2.sp,
            )
        }
    }
}

internal fun worldBrush(palette: V12Palette): Brush = Brush.verticalGradient(
    listOf(
        palette.background,
        palette.panel.copy(alpha = .72f),
        Color(0xFFFFFFFF),
    ),
)
