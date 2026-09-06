package com.serein.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.serein.reader.R
import com.serein.reader.data.ReaderFont
import com.serein.reader.data.ReaderPreferences
import com.serein.reader.data.ReaderTheme

val LoraFamily = FontFamily(Font(R.font.lora))
val LiterataFamily = FontFamily(Font(R.font.literata))
val AtkinsonFamily = FontFamily(Font(R.font.atkinson_hyperlegible))

fun ReaderFont.toFontFamily(): FontFamily = when (this) {
    ReaderFont.LORA -> LoraFamily
    ReaderFont.LITERATA -> LiterataFamily
    ReaderFont.ATKINSON -> AtkinsonFamily
}

@Immutable
data class SereinPalette(
    val paper: Color,
    val sheet: Color,
    val control: Color,
    val selectedControl: Color,
    val ink: Color,
    val mutedInk: Color,
    val sage: Color,
    val apricot: Color,
    val line: Color,
    val coverFallback: Color,
)

private val LightPalette = SereinPalette(
    paper = Color(0xFFFBF8F3),
    sheet = Color(0xFFFFFDF9),
    control = Color(0xFFF2EFE9),
    selectedControl = Color(0xFFE3E9E1),
    ink = Color(0xFF102722),
    mutedInk = Color(0xFF747872),
    sage = Color(0xFF5D7964),
    apricot = Color(0xFFC9744C),
    line = Color(0xFFE2DCD2),
    coverFallback = Color(0xFFDCE5DB),
)

private val SepiaPalette = SereinPalette(
    paper = Color(0xFFF3E7D3),
    sheet = Color(0xFFF8EDDB),
    control = Color(0xFFECE0CC),
    selectedControl = Color(0xFFDCE1CD),
    ink = Color(0xFF332A20),
    mutedInk = Color(0xFF776B5C),
    sage = Color(0xFF5F7356),
    apricot = Color(0xFFB76543),
    line = Color(0xFFDCC9AD),
    coverFallback = Color(0xFFD8D6BA),
)

private val DarkPalette = SereinPalette(
    paper = Color(0xFF101815),
    sheet = Color(0xFF18211E),
    control = Color(0xFF222D29),
    selectedControl = Color(0xFF304039),
    ink = Color(0xFFF1E9DB),
    mutedInk = Color(0xFFAAAFA8),
    sage = Color(0xFF9BB59C),
    apricot = Color(0xFFD99169),
    line = Color(0xFF34423C),
    coverFallback = Color(0xFF263832),
)

val LocalSereinPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun SereinTheme(
    preferences: ReaderPreferences,
    content: @Composable () -> Unit,
) {
    val palette = when (preferences.theme) {
        ReaderTheme.LIGHT -> LightPalette
        ReaderTheme.SEPIA -> SepiaPalette
        ReaderTheme.DARK -> DarkPalette
    }
    val colors = if (preferences.theme == ReaderTheme.DARK) {
        darkColorScheme(
            primary = palette.sage,
            onPrimary = Color(0xFF102018),
            secondary = palette.apricot,
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.sheet,
            onSurface = palette.ink,
            surfaceVariant = palette.control,
            onSurfaceVariant = palette.mutedInk,
            outline = palette.line,
        )
    } else {
        lightColorScheme(
            primary = palette.sage,
            onPrimary = Color.White,
            secondary = palette.apricot,
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.sheet,
            onSurface = palette.ink,
            surfaceVariant = palette.control,
            onSurfaceVariant = palette.mutedInk,
            outline = palette.line,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSereinPalette provides palette) {
        MaterialTheme(
            colorScheme = colors,
            typography = MaterialTheme.typography.copy(
                bodyLarge = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Normal,
                ),
                bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
                labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.SansSerif),
                titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.SansSerif),
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif),
                headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
            ),
            content = content,
        )
    }
}
