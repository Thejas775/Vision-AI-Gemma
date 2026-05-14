package com.thejas.visionaireader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.thejas.visionaireader.R

// ── Editorial display serif + utility sans + mono caps captions ──────
// Display: Instrument Serif (free, Google Fonts) — italic verbs.
// UI:      Inter Tight (workhorse sans).
// Mono:    JetBrains Mono for caption taglines.

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val InstrumentSerif = FontFamily(
        Font(GoogleFont("Instrument Serif"), provider, FontWeight.Normal, FontStyle.Normal),
    Font(GoogleFont("Instrument Serif"), provider, FontWeight.Normal, FontStyle.Italic),
)

val InterTight = FontFamily(
    Font(GoogleFont("Inter Tight"), provider, FontWeight.Normal),
    Font(GoogleFont("Inter Tight"), provider, FontWeight.Medium),
    Font(GoogleFont("Inter Tight"), provider, FontWeight.SemiBold),
    Font(GoogleFont("Inter Tight"), provider, FontWeight.Bold),
)

val MonoCaps = FontFamily(
    Font(GoogleFont("JetBrains Mono"), provider, FontWeight.Medium),
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InstrumentSerif,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.2).sp,
        fontWeight = FontWeight.Normal
    ),
    displayMedium = TextStyle(
        fontFamily = InstrumentSerif,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.8).sp,
        fontWeight = FontWeight.Normal
    ),
    titleLarge = TextStyle(
        fontFamily = InterTight,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterTight,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterTight,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterTight,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    labelSmall = TextStyle(
        fontFamily = MonoCaps,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp
    )
)
