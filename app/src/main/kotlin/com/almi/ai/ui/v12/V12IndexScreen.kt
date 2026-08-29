package com.almi.ai.ui.v12

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
internal fun V12IndexScreen(
    language: String,
    personImage: String?,
    bodyReady: Boolean,
    avatarReady: Boolean,
    aiReady: Boolean,
    onFit: () -> Unit,
    onAvatar: () -> Unit,
    onBody: () -> Unit,
    onAi: () -> Unit,
    onControl: () -> Unit,
) {
    val p = V12Palettes.Index
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(worldBrush(p))
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val line = p.edge.copy(alpha = .72f)
            drawLine(line, androidx.compose.ui.geometry.Offset(size.width * .02f, size.height * .17f), androidx.compose.ui.geometry.Offset(size.width * .98f, size.height * .17f), 1f)
            drawLine(line, androidx.compose.ui.geometry.Offset(size.width * .76f, size.height * .18f), androidx.compose.ui.geometry.Offset(size.width * .76f, size.height * .92f), 1f)
            drawCircle(p.signal.copy(alpha = .08f), size.minDimension * .42f, androidx.compose.ui.geometry.Offset(size.width * .16f, size.height * .48f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text("ALMI", color = p.ink, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.4).sp)
                Text("FASHION OPERATING SYSTEM / 12", color = p.muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Surface(
                modifier = Modifier.size(46.dp).clickable(onClick = onControl),
                shape = CircleShape,
                color = p.panel,
                border = BorderStroke(1.dp, p.edge),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    V12Glyph(V12GlyphType.CONTROL, p.ink, Modifier.size(22.dp))
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 91.dp)
                .height(604.dp),
        ) {
            val fitWidth = maxWidth * .72f
            val avatarWidth = maxWidth * .31f

            Surface(
                modifier = Modifier
                    .width(fitWidth)
                    .height(420.dp)
                    .align(Alignment.TopStart)
                    .clickable(onClick = onFit),
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 58.dp, bottomEnd = 16.dp, bottomStart = 36.dp),
                color = Color(0xFF12110F),
                border = BorderStroke(1.dp, Color(0xFF2A2722)),
                shadowElevation = 8.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (personImage != null) {
                        AsyncImage(
                            model = personImage,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)))
                    }
                    Column(
                        modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("01 / FIT", color = Color(0xFFFF6C3A), fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                        Text(
                            if (language == "ar") "جرّبها\nعليك" else "TRY IT\nON YOU",
                            color = Color(0xFFF5EFE7),
                            fontSize = if (language == "ar") 34.sp else 39.sp,
                            lineHeight = if (language == "ar") 38.sp else 38.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1.4).sp,
                        )
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFFFF6C3A),
                    ) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            V12Glyph(V12GlyphType.FIT, Color(0xFF1B0B05), Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(if (language == "ar") "ابدأ التجربة" else "ENTER FIT", color = Color(0xFF1B0B05), fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Text(
                        if (personImage != null) "PERSON / READY" else "PERSON / EMPTY",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(15.dp),
                        color = Color.White.copy(alpha = .44f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = .8.sp,
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .width(avatarWidth)
                    .height(334.dp)
                    .align(Alignment.TopEnd)
                    .offset(y = 58.dp)
                    .clickable(onClick = onAvatar),
                shape = RoundedCornerShape(topStart = 52.dp, topEnd = 16.dp, bottomStart = 18.dp, bottomEnd = 44.dp),
                color = V12Palettes.Avatar.panel,
                border = BorderStroke(1.dp, V12Palettes.Avatar.edge),
                shadowElevation = 10.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = V12Palettes.Avatar.signal.copy(alpha = .12f)) {
                            V12Glyph(V12GlyphType.AVATAR, V12Palettes.Avatar.signal, Modifier.padding(12.dp).size(31.dp))
                        }
                        Text("02", color = V12Palettes.Avatar.signal, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                    Text(
                        if (language == "ar") "شخصيتي" else "AVATAR",
                        color = V12Palettes.Avatar.ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (avatarReady) "READY" else "BUILD",
                        color = V12Palettes.Avatar.muted,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .width(maxWidth * .67f)
                    .height(142.dp)
                    .align(Alignment.BottomStart)
                    .clickable(onClick = onBody),
                shape = RoundedCornerShape(topStart = 36.dp, topEnd = 10.dp, bottomStart = 10.dp, bottomEnd = 40.dp),
                color = V12Palettes.Body.panel,
                border = BorderStroke(1.dp, V12Palettes.Body.edge),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), color = V12Palettes.Body.background) {
                        V12Glyph(V12GlyphType.BODY, V12Palettes.Body.signal, Modifier.padding(13.dp).size(42.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("03 / BODY MAP", color = V12Palettes.Body.signal, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                        Text(if (language == "ar") "قياساتك" else "YOUR BODY", color = V12Palettes.Body.ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (bodyReady) (if (language == "ar") "ملف الجسم جاهز" else "Profile is calibrated") else (if (language == "ar") "ابدأ المعايرة" else "Start calibration"),
                            color = V12Palettes.Body.muted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .size(126.dp)
                    .align(Alignment.BottomEnd)
                    .offset(y = (-8).dp)
                    .clickable(onClick = onAi),
                shape = CircleShape,
                color = V12Palettes.Ai.panel,
                border = BorderStroke(1.dp, V12Palettes.Ai.edge),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    V12Glyph(V12GlyphType.AI, V12Palettes.Ai.signal, Modifier.size(31.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("AI", color = V12Palettes.Ai.ink, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Text(if (aiReady) "ONLINE" else "SETUP", color = V12Palettes.Ai.muted, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }

        Text(
            if (language == "ar") "لا توجد قوائم سفلية. اختر العالم الذي تريد الدخول إليه." else "NO TAB BAR. PICK A WORLD.",
            modifier = Modifier.align(Alignment.BottomStart).widthIn(max = 270.dp),
            color = p.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .5.sp,
            lineHeight = 13.sp,
        )
    }
}
