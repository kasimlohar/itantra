package com.itantra.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.data.perf.DevicePerf
import com.itantra.presentation.theme.ITantraColors
import com.itantra.presentation.theme.ITantraShapes
import com.itantra.presentation.theme.ITantraType

private const val TAGLINE = "Benchmarked on ₹7,000 phones · Helio G36 · 2 GB RAM"
private const val SECTION_LABEL = "LIVE PERFORMANCE"

/**
 * PerfHudCard — collapsible card shown on HomeScreen.
 *
 * Collapsed: shows "Live Performance" header + chevron toggle.
 * Expanded:  shows animated App RAM/Storage bars + Pipeline latency row + tagline.
 *
 * App RAM bar:     green < 70 %, amber < 90 %, red ≥ 90 %
 * App Storage bar: blue progress (used / total)
 * Pipeline row:    total VAD→STT→TX→TTS in ms (no bar)
 *
 * @param perf      null → shows skeleton placeholders
 * @param expanded  whether the card body is visible
 * @param onToggleExpand called when the header row is tapped
 */
@Composable
fun PerfHudCard(
    perf: DevicePerf?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(ITantraShapes.Card),
        shape = ITantraShapes.Card,
        color = ITantraColors.Surface,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // ── Header row ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Performance HUD header" },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = SECTION_LABEL,
                        style = ITantraType.caption.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = ITantraColors.TextSecondary
                        )
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                  else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = ITantraColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Animated body ─────────────────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = ITantraColors.Border, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    if (perf == null) {
                        // Skeleton rows
                        repeat(3) {
                            SkeletonMetricRow()
                            Spacer(Modifier.height(10.dp))
                        }
                    } else {
                        val appRamMb = perf.appRamMb
                        val appRamFraction = if (perf.ramTotalMb > 0)
                            appRamMb.toFloat() / perf.ramTotalMb.toFloat() else 0f
                        val appStorageUsedMb = perf.appStorageUsedMb
                        val storageFraction = if (perf.storageTotalGb > 0f)
                            (appStorageUsedMb / 1024f) / perf.storageTotalGb else 0f

                        MetricRow(
                            icon = Icons.Default.Memory,
                            label = "App RAM",
                            value = "%d MB".format(appRamMb),
                            fraction = appRamFraction,
                            barColor = when {
                                appRamFraction >= 0.90f -> ITantraColors.Error
                                appRamFraction >= 0.70f -> ITantraColors.Amber
                                else -> ITantraColors.Success
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        MetricRow(
                            icon = Icons.Default.Storage,
                            label = "App Storage",
                            value = if (appStorageUsedMb < 1024) "${appStorageUsedMb} MB"
                                    else "%.1f GB".format(appStorageUsedMb / 1024f),
                            fraction = storageFraction.coerceIn(0f, 1f),
                            barColor = ITantraColors.Blue
                        )
                        Spacer(Modifier.height(10.dp))
                        // Pipeline latency row (VAD → STT → TX → TTS)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = "Pipeline Latency",
                                    tint = ITantraColors.TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Pipeline",
                                    style = ITantraType.bodySmall.copy(
                                        color = ITantraColors.TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (perf.pipelineLatencyMs < 0L) "—"
                                           else "${perf.pipelineLatencyMs} ms",
                                    style = ITantraType.bodySmall.copy(
                                        color = ITantraColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    text = "VAD → STT → TX → TTS",
                                    style = ITantraType.caption.copy(
                                        fontSize = 10.sp,
                                        color = ITantraColors.TextSecondary
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = ITantraColors.Border, thickness = 0.5.dp)

                    // Tagline
                    Text(
                        text = TAGLINE,
                        style = ITantraType.caption.copy(fontSize = 11.sp),
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

// ── Metric row ────────────────────────────────────────────────────────────────
@Composable
private fun MetricRow(
    icon: ImageVector,
    label: String,
    value: String,
    fraction: Float,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "$label bar"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = ITantraColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label,
                    style = ITantraType.bodySmall.copy(
                        color = ITantraColors.TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            Text(
                text = value,
                style = ITantraType.bodySmall.copy(
                    color = ITantraColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(ITantraShapes.Pill)
                .semantics { contentDescription = "$label usage bar" },
            color = barColor,
            trackColor = ITantraColors.Border,
            strokeCap = StrokeCap.Round
        )
    }
}

// ── Skeleton row (loading placeholder) ───────────────────────────────────────
@Composable
private fun SkeletonMetricRow(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier
                    .width(80.dp)
                    .height(14.dp)
                    .clip(ITantraShapes.Pill)
                    .background(ITantraColors.Border)
            )
            Box(
                Modifier
                    .width(60.dp)
                    .height(14.dp)
                    .clip(ITantraShapes.Pill)
                    .background(ITantraColors.Border)
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(ITantraShapes.Pill)
                .background(ITantraColors.Border)
        )
    }
}
