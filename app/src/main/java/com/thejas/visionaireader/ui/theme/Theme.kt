package com.thejas.visionaireader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val InkAndAmber = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Amber,
    onSecondary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkSecondary,
    outline = Hairline,
    error = Vermilion,
    onError = Paper,
    tertiary = Moss,
    onTertiary = Paper
)

@Composable
fun VisionAIReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InkAndAmber,
        typography = AppTypography,
        content = content
    )
}
