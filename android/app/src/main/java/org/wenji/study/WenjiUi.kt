package org.wenji.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object WenjiColors {
    val Background = Color(0xFFF6F7F4)
    val Ink = Color(0xFF20313D)
    val Muted = Color(0xFF627179)
    val Teal = Color(0xFF267A73)
    val TealSoft = Color(0xFFE8F3EF)
    val Border = Color(0xFFE3E8E4)
    val Field = Color(0xFFF7F9F7)
    val Amber = Color(0xFF956323)
    val AmberSoft = Color(0xFFFBF3E4)
    val Red = Color(0xFFA54F49)
    val RedSoft = Color(0xFFFCF0ED)
}

@Composable
internal fun WenjiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = WenjiColors.Teal, onPrimary = Color.White,
            primaryContainer = WenjiColors.TealSoft, onPrimaryContainer = WenjiColors.Teal,
            background = WenjiColors.Background, onBackground = WenjiColors.Ink,
            surface = Color.White, onSurface = WenjiColors.Ink,
            surfaceVariant = WenjiColors.Field, onSurfaceVariant = WenjiColors.Muted,
            outline = WenjiColors.Border, outlineVariant = WenjiColors.Border,
            error = WenjiColors.Red, errorContainer = WenjiColors.RedSoft,
            secondary = WenjiColors.Teal, secondaryContainer = WenjiColors.TealSoft,
            onSecondaryContainer = WenjiColors.Teal, surfaceTint = Color.Transparent,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFAFBF8),
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = WenjiColors.Background
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        ),
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
        content = content
    )
}

internal enum class Glyph { Question, Book, Review, Plus, Arrow, Down, Right, Sync, File, Search, More, Check, Info, Clock }

@Composable
internal fun LineIcon(glyph: Glyph, modifier: Modifier = Modifier.size(22.dp), color: Color = WenjiColors.Muted) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1,y1), Offset(x2,y2), 1.65f, StrokeCap.Round)
            fun path(vararg points: Pair<Float,Float>) {
                val p = Path(); points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.first,pt.second) else p.lineTo(pt.first,pt.second) }; drawPath(p,color,style=stroke)
            }
            when (glyph) {
                Glyph.Question -> {
                    drawRoundRect(color,Offset(3f,3f),Size(18f,16f),CornerRadius(5f),style=stroke)
                    path(7f to 19f, 7f to 22f, 11f to 19f)
                    val p=Path(); p.moveTo(9f,8f);p.cubicTo(9f,4.5f,16f,5f,15f,9f);p.cubicTo(14.5f,11f,12f,10f,12f,13f);drawPath(p,color,style=stroke)
                    drawCircle(color,0.9f,Offset(12f,16f))
                }
                Glyph.Book -> { path(12f to 5f,12f to 21f); path(3f to 4f,7f to 4f,12f to 6f,17f to 4f,21f to 4f,21f to 19f,17f to 19f,12f to 21f,7f to 19f,3f to 19f,3f to 4f); line(6f,8f,9f,9f);line(15f,9f,18f,8f) }
                Glyph.Review -> { drawRoundRect(color,Offset(4f,3f),Size(16f,19f),CornerRadius(3f),style=stroke); path(8f to 11f,11f to 14f,17f to 8f);line(8f,18f,16f,18f) }
                Glyph.Plus -> { line(12f,5f,12f,19f);line(5f,12f,19f,12f) }
                Glyph.Arrow -> { line(4f,12f,20f,12f);path(15f to 7f,20f to 12f,15f to 17f) }
                Glyph.Down -> path(7f to 9f,12f to 14f,17f to 9f)
                Glyph.Right -> path(9f to 6f,15f to 12f,9f to 18f)
                Glyph.Sync -> { val p=Path();p.moveTo(4f,9f);p.cubicTo(7f,1f,18f,2f,20f,9f);drawPath(p,color,style=stroke);path(20f to 4f,20f to 9f,15f to 9f);val q=Path();q.moveTo(20f,15f);q.cubicTo(17f,23f,6f,22f,4f,15f);drawPath(q,color,style=stroke);path(4f to 20f,4f to 15f,9f to 15f) }
                Glyph.File -> { path(5f to 2f,14f to 2f,20f to 8f,20f to 22f,5f to 22f,5f to 2f);path(14f to 2f,14f to 8f,20f to 8f);line(9f,13f,16f,13f);line(9f,17f,14f,17f) }
                Glyph.Search -> { drawCircle(color,7f,Offset(10f,10f),style=stroke);line(15f,15f,21f,21f) }
                Glyph.More -> { listOf(5f,12f,19f).forEach { drawCircle(color,1.4f,Offset(it,12f)) } }
                Glyph.Check -> path(5f to 12f,10f to 17f,20f to 7f)
                Glyph.Info -> { drawCircle(color,9f,Offset(12f,12f),style=stroke);line(12f,11f,12f,17f);drawCircle(color,1f,Offset(12f,7f)) }
                Glyph.Clock -> { drawCircle(color,9f,Offset(12f,12f),style=stroke);path(12f to 6f,12f to 12f,16f to 14f) }
            }
        }
    }
}

internal enum class Tone { Neutral, Teal, Amber, Red }
internal fun toneColors(tone: Tone) = when(tone) {
    Tone.Teal -> WenjiColors.Teal to WenjiColors.TealSoft
    Tone.Amber -> WenjiColors.Amber to WenjiColors.AmberSoft
    Tone.Red -> WenjiColors.Red to WenjiColors.RedSoft
    Tone.Neutral -> WenjiColors.Muted to WenjiColors.Field
}
@Composable internal fun Tag(text: String, tone: Tone = Tone.Neutral) {
    val (ink, fill) = toneColors(tone)
    Surface(color = fill, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = ink, style = MaterialTheme.typography.labelMedium)
    }
}
@Composable internal fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp,WenjiColors.Border)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable internal fun SectionTitle(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp,bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if(trailing.isNotBlank()) Text(trailing, color = WenjiColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable internal fun Notice(text: String, tone: Tone = Tone.Amber) {
    val (ink,fill)=toneColors(tone)
    Surface(color=fill,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            LineIcon(Glyph.Info,Modifier.size(18.dp).padding(top=2.dp),ink)
            Text(text,color=ink,style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable internal fun PrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true, glyph: Glyph? = null) {
    Button(onClick=onClick,enabled=enabled,contentPadding=heightPadding(),shape=RoundedCornerShape(12.dp)) {
        if(glyph!=null) { LineIcon(glyph,Modifier.size(18.dp),if(enabled) Color.White else WenjiColors.Muted);Spacer(Modifier.width(8.dp)) }
        Text(text)
    }
}
private fun heightPadding() = PaddingValues(horizontal=18.dp,vertical=12.dp)

@Composable internal fun InputField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, minLines: Int = 1, maxLines: Int = 8, leading: Glyph? = null) {
    OutlinedTextField(value,onChange,modifier=modifier.fillMaxWidth(),placeholder={Text(placeholder,color=WenjiColors.Muted)},minLines=minLines,maxLines=maxLines,
        singleLine = maxLines == 1, shape=RoundedCornerShape(13.dp),textStyle=MaterialTheme.typography.bodyMedium,
        leadingIcon=if(leading!=null) { { LineIcon(leading) } } else null,
        colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=WenjiColors.Field,focusedContainerColor=Color.White,
            unfocusedBorderColor=WenjiColors.Border,focusedBorderColor=WenjiColors.Teal))
}
