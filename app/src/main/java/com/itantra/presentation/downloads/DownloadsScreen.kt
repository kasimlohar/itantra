package com.itantra.presentation.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.itantra.presentation.theme.ITantraColors
import com.itantra.presentation.theme.ITantraShapes
import com.itantra.presentation.theme.ITantraType

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ITantraColors.Background)
    ) {
        // ── Screen header ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Text(
                text = "Downloads",
                style = ITantraType.screenTitle
            )
            Text(
                text = "Offline AI language models",
                style = ITantraType.bodySmall
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ITantraColors.Primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // ── Offline language pack card ────────────────────────────────────
                item {
                    Spacer(Modifier.height(4.dp))
                    LanguagePackCard(
                        downloadableMb = state.totalBundleMb,
                        totalModels = state.models.size,
                        onDownloadBundle = { viewModel.downloadBundle() }
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // ── Section header ─────────────────────────────────────────────
                item {
                    Text(
                        text = "LANGUAGE ENGINES",
                        style = ITantraType.caption.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = ITantraColors.TextSecondary
                        ),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                    )
                }

                // ── Model list ───────────────────────────────────────────────────
                items(state.models, key = { it.id }) { entry ->
                    ModelCard(
                        entry = entry,
                        onDownload = { viewModel.downloadModel(entry) },
                        onDelete = { viewModel.deleteModel(entry) }
                    )
                    if (entry != state.models.lastOrNull()) {
                        HorizontalDivider(
                            color = ITantraColors.Border,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Language pack banner card ──────────────────────────────────────────────────
@Composable
private fun LanguagePackCard(
    downloadableMb: Int,
    totalModels: Int,
    onDownloadBundle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = ITantraShapes.Card,
        color = ITantraColors.Surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "OFFLINE LANGUAGE PACK",
                        style = ITantraType.caption.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = ITantraColors.TextSecondary
                        )
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$totalModels language engines",
                        style = ITantraType.body.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.TextPrimary
                        )
                    )
                }
                if (downloadableMb > 0) {
                    Surface(
                        shape = ITantraShapes.Pill,
                        color = ITantraColors.SurfaceVariant
                    ) {
                        Text(
                            text = "~$downloadableMb MB",
                            style = ITantraType.caption.copy(
                                fontWeight = FontWeight.Medium,
                                color = ITantraColors.TextSecondary
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Includes Silero VAD, AI4Bharat IndicConformer STT engines for 9 Indian languages, FastText language detector, and offline TTS voice packs.",
                style = ITantraType.bodySmall,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDownloadBundle,
                enabled = downloadableMb > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = ITantraShapes.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ITantraColors.Primary,
                    contentColor = Color.White,
                    disabledContainerColor = ITantraColors.SurfaceVariant,
                    disabledContentColor = ITantraColors.TextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (downloadableMb > 0) "Download Full Pack  ·  ${downloadableMb} MB" else "All Models Installed ✓",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

// ── Single model card ──────────────────────────────────────────────────────────
@Composable
private fun ModelCard(
    entry: ModelEntry,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    // Derive a friendly language label from the model ID/name
    val languageLabel = when {
        entry.isVad -> "Voice Detector"
        entry.id == "lid" -> "Language Detector"
        entry.displayName.contains("Hindi") -> "Hindi"
        entry.displayName.contains("Gujarati") -> "Gujarati"
        entry.displayName.contains("Marathi") -> "Marathi"
        entry.displayName.contains("Kannada") -> "Kannada"
        entry.displayName.contains("Malayalam") -> "Malayalam"
        entry.displayName.contains("Tamil") -> "Tamil"
        entry.displayName.contains("Telugu") -> "Telugu"
        entry.displayName.contains("Bengali") -> "Bengali"
        entry.displayName.contains("English") -> "English"
        else -> entry.displayName
    }
    val typeLabel = when {
        entry.isVad -> "Audio detection"
        entry.id == "lid" -> "Language identification"
        else -> "Speech recognition"
    }

    Surface(
        color = ITantraColors.Background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ITantraColors.Surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isVad) Icons.Default.GraphicEq else Icons.Default.Language,
                    contentDescription = null,
                    tint = ITantraColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Language + type + size
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = languageLabel,
                    style = ITantraType.body.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = typeLabel,
                        style = ITantraType.caption,
                        maxLines = 1
                    )
                    Text("·", style = ITantraType.caption)
                    Text(
                        text = "${entry.sizeMb} MB",
                        style = ITantraType.caption.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )
                }
                // Progress bar shown while downloading
                if (entry.installState == ModelInstallState.DOWNLOADING) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { entry.downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = ITantraColors.Primary,
                        trackColor = ITantraColors.Border
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Downloading…",
                        style = ITantraType.caption.copy(color = ITantraColors.TextSecondary),
                        fontSize = 11.sp
                    )
                }
            }

            // Action badge
            when (entry.installState) {
                ModelInstallState.INSTALLED -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(50))
                                .background(ITantraColors.Success),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Installed",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint = ITantraColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                ModelInstallState.DOWNLOADABLE -> {
                    Button(
                        onClick = onDownload,
                        shape = ITantraShapes.Pill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ITantraColors.Primary,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Get",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }

                ModelInstallState.DOWNLOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                        color = ITantraColors.Primary
                    )
                }

                ModelInstallState.FAILED -> {
                    Button(
                        onClick = onDownload,
                        shape = ITantraShapes.Pill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ITantraColors.Error,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("Retry", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
