package com.almi.ai.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.almi.ai.data.preferences.AvatarAppearance
import com.almi.ai.data.preferences.AvatarPresentation
import kotlin.math.min

/**
 * Local deterministic anime-style portrait used by Create Avatar.
 *
 * It is deliberately image-like and non-3D: the same face geometry is redrawn from the same
 * appearance state, while hair/skin/glasses/facial-hair choices change only their own layer.
 * No network request, bitmap decoder or Filament resource is involved.
 */
@Composable
fun AnimeAvatarPortrait(
    appearance: AvatarAppearance,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val unit = min(w, h)
        val cx = w * 0.5f

        val skin = hexColor(appearance.skinColor, Color(0xFFF1C6AF))
        val hair = hexColor(appearance.hairColor, Color(0xFF2C1B18))
        val ink = Color(0xFF202631)
        val white = Color(0xFFFFFEFC)
        val iris = if (appearance.presentation == AvatarPresentation.FEMININE) Color(0xFF4C6F9F) else Color(0xFF41586F)
        val blush = Color(0xFFE98883)

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFF4F6FA), Color(0xFFE8EDF6)),
                startY = 0f,
                endY = h,
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.92f), Color.Transparent),
                center = Offset(cx, h * 0.28f),
                radius = unit * 0.56f,
            ),
            radius = unit * 0.56f,
            center = Offset(cx, h * 0.28f),
        )

        // Hair behind head for medium/long styles.
        if (appearance.hairVariant == "bob" || appearance.hairVariant == "longButNotTooLong") {
            val backHair = Path().apply {
                moveTo(cx - w * 0.23f, h * 0.18f)
                cubicTo(cx - w * 0.31f, h * 0.31f, cx - w * 0.27f, h * 0.61f, cx - w * 0.19f, h * 0.77f)
                cubicTo(cx - w * 0.06f, h * 0.84f, cx + w * 0.06f, h * 0.84f, cx + w * 0.19f, h * 0.77f)
                cubicTo(cx + w * 0.27f, h * 0.61f, cx + w * 0.31f, h * 0.31f, cx + w * 0.23f, h * 0.18f)
                close()
            }
            drawPath(
                path = backHair,
                brush = Brush.verticalGradient(listOf(lighten(hair, 0.10f), darken(hair, 0.16f))),
            )
        }

        // Shoulders / outfit.
        val shoulderY = h * 0.79f
        val outfitPath = Path().apply {
            moveTo(cx - w * 0.38f, h)
            lineTo(cx - w * 0.33f, shoulderY + h * 0.06f)
            cubicTo(cx - w * 0.27f, shoulderY, cx - w * 0.16f, shoulderY - h * 0.03f, cx - w * 0.10f, shoulderY - h * 0.035f)
            lineTo(cx + w * 0.10f, shoulderY - h * 0.035f)
            cubicTo(cx + w * 0.16f, shoulderY - h * 0.03f, cx + w * 0.27f, shoulderY, cx + w * 0.33f, shoulderY + h * 0.06f)
            lineTo(cx + w * 0.38f, h)
            close()
        }
        drawPath(
            path = outfitPath,
            brush = Brush.verticalGradient(listOf(Color(0xFF242B38), Color(0xFF111722))),
        )

        // Neck.
        drawRoundRect(
            color = skin,
            topLeft = Offset(cx - w * 0.065f, h * 0.64f),
            size = Size(w * 0.13f, h * 0.17f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.035f),
        )

        // Face.
        val faceWidth = if (appearance.presentation == AvatarPresentation.FEMININE) w * 0.35f else w * 0.37f
        val faceLeft = cx - faceWidth / 2f
        val faceTop = h * 0.21f
        val faceHeight = h * 0.49f
        drawOval(
            brush = Brush.verticalGradient(listOf(lighten(skin, 0.08f), skin, darken(skin, 0.05f))),
            topLeft = Offset(faceLeft, faceTop),
            size = Size(faceWidth, faceHeight),
        )

        // Ears.
        drawOval(color = skin, topLeft = Offset(faceLeft - w * 0.025f, h * 0.40f), size = Size(w * 0.055f, h * 0.12f))
        drawOval(color = skin, topLeft = Offset(faceLeft + faceWidth - w * 0.030f, h * 0.40f), size = Size(w * 0.055f, h * 0.12f))

        // Hair front changes locally while identity stays fixed.
        when (appearance.hairVariant) {
            "shortCurly" -> {
                val r = w * 0.052f
                val centers = listOf(
                    Offset(cx - w * 0.15f, h * 0.25f), Offset(cx - w * 0.09f, h * 0.20f),
                    Offset(cx - w * 0.025f, h * 0.185f), Offset(cx + w * 0.045f, h * 0.19f),
                    Offset(cx + w * 0.11f, h * 0.22f), Offset(cx + w * 0.16f, h * 0.27f),
                    Offset(cx - w * 0.13f, h * 0.32f), Offset(cx - w * 0.055f, h * 0.29f),
                    Offset(cx + w * 0.025f, h * 0.285f), Offset(cx + w * 0.105f, h * 0.31f),
                )
                centers.forEachIndexed { index, c ->
                    drawCircle(
                        color = if (index % 2 == 0) lighten(hair, 0.05f) else hair,
                        radius = r,
                        center = c,
                    )
                }
            }
            "bob" -> {
                val fringe = Path().apply {
                    moveTo(cx - w * 0.19f, h * 0.30f)
                    cubicTo(cx - w * 0.13f, h * 0.13f, cx + w * 0.11f, h * 0.12f, cx + w * 0.19f, h * 0.30f)
                    cubicTo(cx + w * 0.11f, h * 0.25f, cx + w * 0.05f, h * 0.27f, cx, h * 0.32f)
                    cubicTo(cx - w * 0.05f, h * 0.26f, cx - w * 0.11f, h * 0.25f, cx - w * 0.19f, h * 0.30f)
                    close()
                }
                drawPath(fringe, brush = Brush.verticalGradient(listOf(lighten(hair, 0.08f), hair)))
                drawRoundRect(
                    color = hair,
                    topLeft = Offset(cx - w * 0.215f, h * 0.28f),
                    size = Size(w * 0.055f, h * 0.31f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
                )
                drawRoundRect(
                    color = hair,
                    topLeft = Offset(cx + w * 0.16f, h * 0.28f),
                    size = Size(w * 0.055f, h * 0.31f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
                )
            }
            "longButNotTooLong" -> {
                val fringe = Path().apply {
                    moveTo(cx - w * 0.19f, h * 0.31f)
                    cubicTo(cx - w * 0.16f, h * 0.14f, cx + w * 0.13f, h * 0.13f, cx + w * 0.19f, h * 0.31f)
                    cubicTo(cx + w * 0.08f, h * 0.26f, cx + w * 0.03f, h * 0.33f, cx - w * 0.02f, h * 0.36f)
                    cubicTo(cx - w * 0.05f, h * 0.28f, cx - w * 0.12f, h * 0.25f, cx - w * 0.19f, h * 0.31f)
                    close()
                }
                drawPath(fringe, brush = Brush.verticalGradient(listOf(lighten(hair, 0.08f), hair)))
            }
            else -> {
                val short = Path().apply {
                    moveTo(cx - w * 0.19f, h * 0.31f)
                    cubicTo(cx - w * 0.17f, h * 0.17f, cx - w * 0.02f, h * 0.13f, cx + w * 0.12f, h * 0.18f)
                    cubicTo(cx + w * 0.18f, h * 0.20f, cx + w * 0.20f, h * 0.28f, cx + w * 0.18f, h * 0.34f)
                    cubicTo(cx + w * 0.08f, h * 0.27f, cx - w * 0.05f, h * 0.28f, cx - w * 0.19f, h * 0.31f)
                    close()
                }
                drawPath(short, brush = Brush.verticalGradient(listOf(lighten(hair, 0.08f), hair)))
            }
        }

        // Brows.
        val eyeY = h * 0.43f
        val eyeDx = w * 0.082f
        val browY = eyeY - h * 0.055f
        drawLine(ink, Offset(cx - eyeDx - w * 0.045f, browY), Offset(cx - eyeDx + w * 0.045f, browY - h * 0.008f), strokeWidth = unit * 0.008f, cap = StrokeCap.Round)
        drawLine(ink, Offset(cx + eyeDx - w * 0.045f, browY - h * 0.008f), Offset(cx + eyeDx + w * 0.045f, browY), strokeWidth = unit * 0.008f, cap = StrokeCap.Round)

        // Anime eyes.
        listOf(cx - eyeDx, cx + eyeDx).forEach { ex ->
            drawOval(
                color = white,
                topLeft = Offset(ex - w * 0.050f, eyeY - h * 0.031f),
                size = Size(w * 0.100f, h * 0.070f),
            )
            drawOval(
                color = ink,
                topLeft = Offset(ex - w * 0.050f, eyeY - h * 0.031f),
                size = Size(w * 0.100f, h * 0.070f),
                style = Stroke(width = unit * 0.007f),
            )
            drawCircle(iris, radius = w * 0.027f, center = Offset(ex, eyeY + h * 0.002f))
            drawCircle(ink, radius = w * 0.014f, center = Offset(ex, eyeY + h * 0.004f))
            drawCircle(Color.White, radius = w * 0.006f, center = Offset(ex - w * 0.008f, eyeY - h * 0.006f))
        }

        // Nose and blush.
        drawLine(
            color = darken(skin, 0.17f),
            start = Offset(cx + w * 0.004f, h * 0.48f),
            end = Offset(cx - w * 0.010f, h * 0.545f),
            strokeWidth = unit * 0.005f,
            cap = StrokeCap.Round,
        )
        drawOval(blush.copy(alpha = 0.18f), Offset(cx - w * 0.155f, h * 0.525f), Size(w * 0.085f, h * 0.035f))
        drawOval(blush.copy(alpha = 0.18f), Offset(cx + w * 0.070f, h * 0.525f), Size(w * 0.085f, h * 0.035f))

        // Mouth.
        when (appearance.mouthVariant) {
            "serious" -> drawLine(ink, Offset(cx - w * 0.033f, h * 0.605f), Offset(cx + w * 0.033f, h * 0.605f), unit * 0.006f, cap = StrokeCap.Round)
            else -> drawArc(
                color = Color(0xFF9D4C52),
                startAngle = 12f,
                sweepAngle = 156f,
                useCenter = false,
                topLeft = Offset(cx - w * 0.045f, h * 0.575f),
                size = Size(w * 0.09f, h * 0.055f),
                style = Stroke(unit * 0.006f, cap = StrokeCap.Round),
            )
        }

        // Glasses are an optional local layer.
        if (appearance.accessoriesVariant != "none") {
            val frame = Color(0xFF252A31)
            val round = appearance.accessoriesVariant == "round"
            val gw = if (round) w * 0.105f else w * 0.125f
            val gh = if (round) h * 0.082f else h * 0.070f
            listOf(cx - eyeDx, cx + eyeDx).forEach { gx ->
                drawOval(
                    color = frame,
                    topLeft = Offset(gx - gw / 2f, eyeY - gh / 2f),
                    size = Size(gw, gh),
                    style = Stroke(unit * 0.009f),
                )
            }
            drawLine(frame, Offset(cx - w * 0.025f, eyeY), Offset(cx + w * 0.025f, eyeY), unit * 0.008f, cap = StrokeCap.Round)
        }

        // Facial hair only for masculine presentation.
        if (appearance.presentation == AvatarPresentation.MASCULINE && appearance.facialHairVariant != "none") {
            val beard = hair.copy(alpha = 0.78f)
            if (appearance.facialHairVariant == "moustacheFancy") {
                drawArc(beard, 8f, 164f, false, Offset(cx - w * 0.060f, h * 0.535f), Size(w * 0.060f, h * 0.045f), style = Stroke(unit * 0.012f, cap = StrokeCap.Round))
                drawArc(beard, 8f, 164f, false, Offset(cx, h * 0.535f), Size(w * 0.060f, h * 0.045f), style = Stroke(unit * 0.012f, cap = StrokeCap.Round))
            } else {
                drawArc(
                    color = beard,
                    startAngle = 10f,
                    sweepAngle = 160f,
                    useCenter = false,
                    topLeft = Offset(cx - w * 0.115f, h * 0.505f),
                    size = Size(w * 0.23f, h * 0.16f),
                    style = Stroke(unit * 0.020f, cap = StrokeCap.Round),
                )
            }
        }

        // Editorial portrait frame.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset(unit * 0.015f, unit * 0.015f),
            size = Size(w - unit * 0.03f, h - unit * 0.03f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.07f),
            style = Stroke(width = unit * 0.004f),
        )
    }
}

private fun hexColor(raw: String, fallback: Color): Color {
    val hex = raw.removePrefix("#").takeLast(6)
    val value = hex.toLongOrNull(16) ?: return fallback
    return Color(
        red = ((value shr 16) and 0xFF) / 255f,
        green = ((value shr 8) and 0xFF) / 255f,
        blue = (value and 0xFF) / 255f,
        alpha = 1f,
    )
}

private fun lighten(color: Color, amount: Float): Color = Color(
    red = color.red + (1f - color.red) * amount,
    green = color.green + (1f - color.green) * amount,
    blue = color.blue + (1f - color.blue) * amount,
    alpha = color.alpha,
)

private fun darken(color: Color, amount: Float): Color = Color(
    red = color.red * (1f - amount),
    green = color.green * (1f - amount),
    blue = color.blue * (1f - amount),
    alpha = color.alpha,
)
