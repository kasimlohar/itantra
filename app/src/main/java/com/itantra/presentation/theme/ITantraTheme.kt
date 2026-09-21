package com.itantra.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Color tokens ─────────────────────────────────────────────────────────────
object ITantraColors {
    val Primary         = Color(0xFF000000)
    val Background      = Color(0xFFFFFFFF)
    val Surface         = Color(0xFFF5F6F7)
    val SurfaceVariant  = Color(0xFFEEEFF1)
    val Border          = Color(0xFFE5E5E5)
    val TextPrimary     = Color(0xFF111111)
    val TextSecondary   = Color(0xFF6F6F6F)
    val Success         = Color(0xFF16A34A)
    val Error           = Color(0xFFDC2626)
    val Amber           = Color(0xFFD97706)
    val Blue            = Color(0xFF2563EB)
    val NavBackground   = Color(0xFFFFFFFF)
    val FloatingBtn     = Color(0xFF000000)
}

// ── Shape tokens ──────────────────────────────────────────────────────────────
object ITantraShapes {
    val Card   = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(12.dp)
    val Pill   = RoundedCornerShape(50.dp)
    val Input  = RoundedCornerShape(24.dp)
    val Small  = RoundedCornerShape(8.dp)
}

// ── Typography tokens ─────────────────────────────────────────────────────────
object ITantraType {
    val screenTitle = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        color = ITantraColors.TextPrimary
    )
    val sectionTitle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = ITantraColors.TextPrimary
    )
    val body = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        color = ITantraColors.TextPrimary
    )
    val bodySmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        color = ITantraColors.TextSecondary
    )
    val buttonLabel = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
    val navLabel = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium
    )
    val caption = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        color = ITantraColors.TextSecondary
    )
}
