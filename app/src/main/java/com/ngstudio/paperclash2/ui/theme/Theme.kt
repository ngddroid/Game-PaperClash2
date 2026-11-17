package com.ngstudio.paperclash2.ui.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ngstudio.paperclash2.R
import androidx.compose.foundation.text.selection.LocalTextSelectionColors

/** Paper Clash 2 palette. */
object PC {
    val Cream = Color(0xFFFFF6E6)
    val Paper = Color(0xFFFFFDF8)
    val PaperDim = Color(0xFFF3EAD8)
    val Ink = Color(0xFF1F2A4A)
    val InkSoft = Color(0xFF3A4A75)
    val InkFaint = Color(0xFF8A93AD)
    val Coral = Color(0xFFFF6B57)
    val CoralDark = Color(0xFFD9483A)
    val Sun = Color(0xFFFFC83D)
    val SunDark = Color(0xFFE09A12)
    val Mint = Color(0xFF2EC4A0)
    val MintDark = Color(0xFF1E9B7D)
    val Sky = Color(0xFF4BA3FF)
    val SkyDark = Color(0xFF2B78D6)
    val Grape = Color(0xFF7A5CFF)
    val GrapeDark = Color(0xFF5A3FD6)
    val Pink = Color(0xFFFF7AB6)
    val PinkDark = Color(0xFFDB4F8E)
    val Kraft = Color(0xFFC89B6D)
    val KraftDark = Color(0xFF9E7246)
    val Mat = Color(0xFF2F8F6B)
    val Shadow = Color(0x331F2A4A)
    val Locked = Color(0xFFB9BFCF)
}

val Display = FontFamily(Font(R.font.baloo2_extrabold, FontWeight.ExtraBold))
val Body = FontFamily(Font(R.font.fredoka_semibold, FontWeight.SemiBold), Font(R.font.fredoka_bold, FontWeight.Bold))

object Type {
    val hero = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 42.sp, color = PC.Ink)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 30.sp, color = PC.Ink)
    val heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 24.sp, color = PC.Ink)
    val button = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 22.sp, color = Color.White)
    val label = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp, color = PC.Ink)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, color = PC.InkSoft)
    val small = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp, color = PC.InkSoft)
    val number = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 20.sp, color = PC.Ink)
}

@Composable
fun PaperClashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PC.Coral,
            onPrimary = Color.White,
            secondary = PC.Sky,
            background = PC.Cream,
            surface = PC.Paper,
            onSurface = PC.Ink,
            onBackground = PC.Ink,
        ),
    ) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(handleColor = PC.Coral, backgroundColor = PC.Coral.copy(alpha = 0.3f)),
            content = content,
        )
    }
}
