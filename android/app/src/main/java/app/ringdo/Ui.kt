package app.ringdo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ink {
    val bg = Color(0xFF14110F)
    val surface = Color(0xFF1D1916)
    val surface2 = Color(0xFF26211D)
    val line = Color(0xFF3A332D)
    val text = Color(0xFFF4ECE4)
    val muted = Color(0xFFA79A8D)
    val faint = Color(0xFF6F655B)
    val accent = Color(0xFFFF6A3D)
    val accentInk = Color(0xFF1A0D07)
    val ok = Color(0xFF7BD88F)
    val warn = Color(0xFFFFC857)
}

val Mono = FontFamily.Monospace

/** Alarm-clock mark drawn on canvas (bells + face + hands), scales cleanly. */
@Composable
fun AlarmGlyph(color: Color, size: Dp, check: Boolean = false, checkColor: Color = color) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 32f
        val sw = 2.8f * u
        drawCircle(color, radius = 9f * u, center = Offset(16f * u, 18f * u), style = Stroke(sw))
        drawLine(color, Offset(5f * u, 8f * u), Offset(9f * u, 5f * u), sw, StrokeCap.Round)
        drawLine(color, Offset(27f * u, 8f * u), Offset(23f * u, 5f * u), sw, StrokeCap.Round)
        if (check) {
            drawLine(checkColor, Offset(12.4f * u, 18f * u), Offset(15f * u, 20.6f * u), sw, StrokeCap.Round)
            drawLine(checkColor, Offset(15f * u, 20.6f * u), Offset(19.6f * u, 15.6f * u), sw, StrokeCap.Round)
        } else {
            drawLine(color, Offset(16f * u, 13f * u), Offset(16f * u, 18f * u), sw, StrokeCap.Round)
            drawLine(color, Offset(16f * u, 18f * u), Offset(19f * u, 20f * u), sw, StrokeCap.Round)
        }
    }
}

/** kind: 0 = neutral, 1 = primary (accent), 2 = ghost */
@Composable
fun BigButton(label: String, modifier: Modifier = Modifier, kind: Int = 0, small: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (small) 11.dp else 16.dp)
    val bg = when (kind) { 1 -> Ink.accent; 2 -> Color.Transparent; else -> Ink.surface2 }
    val fg = if (kind == 1) Ink.accentInk else Ink.text
    Box(
        modifier.clip(shape).background(bg).border(BorderStroke(1.dp, if (kind == 1) Ink.accent else Ink.line), shape)
            .clickable(onClick = onClick).padding(vertical = if (small) 12.dp else 18.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = if (small) 14.sp else 17.sp) }
}

@Composable
fun Pill(label: String, onClick: () -> Unit, selected: Boolean = false) {
    val shape = RoundedCornerShape(99.dp)
    Box(
        Modifier.clip(shape).background(if (selected) Ink.surface2 else Color.Transparent)
            .border(1.dp, if (selected) Ink.accent else Ink.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(label, color = if (selected) Ink.text else Ink.muted, fontSize = 13.sp) }
}

@Composable
fun SubRow(s: Sub, onToggle: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (s.done) Ink.ok else Color.Transparent)
                .border(2.dp, if (s.done) Ink.ok else Ink.line, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) { if (s.done) Text("✓", color = Ink.bg, fontSize = 12.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(12.dp))
        Text(s.text, color = if (s.done) Ink.faint else Ink.text, fontSize = 15.sp,
            textDecoration = if (s.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
    }
}

@Composable
fun ListDot(color: Long?, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(if (color != null) Color(color) else Ink.faint, androidx.compose.foundation.shape.CircleShape))
}

@Composable
fun RingdoTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Ink.accent, onPrimary = Ink.accentInk, background = Ink.bg, onBackground = Ink.text,
            surface = Ink.surface, onSurface = Ink.text, surfaceVariant = Ink.surface2, onSurfaceVariant = Ink.muted,
            outline = Ink.line, inverseSurface = Ink.text, inverseOnSurface = Ink.bg, inversePrimary = Ink.accent,
            surfaceContainer = Ink.surface, surfaceContainerHigh = Ink.surface2, surfaceContainerHighest = Ink.surface2,
        ),
        content = content
    )
}
