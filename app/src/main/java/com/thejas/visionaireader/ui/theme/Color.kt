package com.thejas.visionaireader.ui.theme

import androidx.compose.ui.graphics.Color

// ── Ink & Amber palette ─────────────────────────────────────────────
// Warm paper surfaces, ink primary, single amber accent.
// No third "card grey". No gradients on chrome.

val Paper       = Color(0xFFF4EFE6)   // Warm paper background
val PaperDim    = Color(0xFFE8E1D3)   // Pressed states / dividers
val Ink         = Color(0xFF0E0E0C)   // Warm off-black primary
val InkSecondary= Color(0xFF4A453D)   // Body / captions
val Hairline    = Color(0x1F1A1A18)   // 12% ink — borders only

val Amber       = Color(0xFFFF7A1A)   // Read accent — one per screen
val AmberDim    = Color(0xFFE56A0F)   // Pressed amber
val Vermilion   = Color(0xFFE5341A)   // Recording / live state
val Moss        = Color(0xFF4F6B3A)   // Success

// On-surface helpers
val OnAmber     = Ink                 // ink on amber surface
val OnInk       = Paper               // paper on ink surface
