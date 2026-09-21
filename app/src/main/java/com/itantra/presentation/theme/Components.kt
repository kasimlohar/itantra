package com.itantra.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Status pill types ─────────────────────────────────────────────────────────
enum class PillType { STANDBY, CONNECTED, SEARCHING, ERROR }

// ── AppHeader ─────────────────────────────────────────────────────────────────
/**
 * Standard screen header used on Radar, Transceiver etc.
 * title: large bold heading
 * subtitle: small gray subtext (e.g. device ID, status)
 * topEnd: trailing slot for pills / chips
 */
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    topEnd: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = ITantraType.screenTitle
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = ITantraType.bodySmall
                )
            }
        }
        if (topEnd != null) {
            Spacer(Modifier.width(12.dp))
            Box(contentAlignment = Alignment.TopEnd) {
                topEnd()
            }
        }
    }
}

// ── StatusPill ────────────────────────────────────────────────────────────────
@Composable
fun StatusPill(
    label: String,
    pillType: PillType,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, dotColor) = when (pillType) {
        PillType.STANDBY    -> Triple(Color.Transparent, ITantraColors.TextSecondary, ITantraColors.TextSecondary)
        PillType.CONNECTED  -> Triple(ITantraColors.Success.copy(alpha = 0.1f), ITantraColors.Success, ITantraColors.Success)
        PillType.SEARCHING  -> Triple(Color.Transparent, ITantraColors.Amber, ITantraColors.Amber)
        PillType.ERROR      -> Triple(ITantraColors.Error.copy(alpha = 0.1f), ITantraColors.Error, ITantraColors.Error)
    }

    val borderColor = when (pillType) {
        PillType.STANDBY   -> ITantraColors.Border
        PillType.CONNECTED -> ITantraColors.Success.copy(alpha = 0.3f)
        PillType.SEARCHING -> ITantraColors.Amber.copy(alpha = 0.5f)
        PillType.ERROR     -> ITantraColors.Error.copy(alpha = 0.3f)
    }

    // Pulsing dot for SEARCHING
    val infiniteTransition = rememberInfiniteTransition(label = "pill")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot"
    )

    Row(
        modifier = modifier
            .border(1.dp, borderColor, ITantraShapes.Pill)
            .background(bgColor, ITantraShapes.Pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = if (pillType == PillType.SEARCHING) dotAlpha else 1f))
        )
        Text(
            text = label,
            style = ITantraType.bodySmall.copy(
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        )
    }
}

// ── PrimaryButton ─────────────────────────────────────────────────────────────
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ITantraShapes.Button,
        colors = ButtonDefaults.buttonColors(
            containerColor = ITantraColors.Primary,
            contentColor = Color.White,
            disabledContainerColor = ITantraColors.SurfaceVariant,
            disabledContentColor = ITantraColors.TextSecondary
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        modifier = modifier.height(48.dp)
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = ITantraType.buttonLabel.copy(color = Color.White))
    }
}

// ── SecondaryButton ───────────────────────────────────────────────────────────
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ITantraShapes.Button,
        border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.Border),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        modifier = modifier.height(48.dp)
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = ITantraType.buttonLabel.copy(color = ITantraColors.TextPrimary))
    }
}

// ── LanguageChip ──────────────────────────────────────────────────────────────
@Composable
fun LanguageChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) ITantraColors.Primary else Color.Transparent,
        animationSpec = tween(150),
        label = "chipBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else ITantraColors.TextPrimary,
        animationSpec = tween(150),
        label = "chipText"
    )
    val borderColor = if (isSelected) ITantraColors.Primary else ITantraColors.Border

    Box(
        modifier = modifier
            .border(1.dp, borderColor, ITantraShapes.Pill)
            .background(bgColor, ITantraShapes.Pill)
            .clip(ITantraShapes.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = ITantraType.body.copy(
                color = textColor,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp
            )
        )
    }
}

// ── SectionHeader ─────────────────────────────────────────────────────────────
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = ITantraType.sectionTitle,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}
