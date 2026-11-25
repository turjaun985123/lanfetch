package com.lanfetch.app.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanfetch.app.R
import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.LiveTitle
import com.lanfetch.app.download.Format
import com.lanfetch.app.ui.components.LanesBadge
import com.lanfetch.app.ui.components.LanesDivider
import com.lanfetch.app.ui.components.LanesPoster
import com.lanfetch.app.ui.theme.TabularBodySmall
import com.lanfetch.app.ui.theme.LanesPalette
import com.lanfetch.app.ui.theme.LanesRadii
import com.lanfetch.app.ui.theme.LanesSizes
import com.lanfetch.app.ui.theme.LanesSpacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaDetailSheet(
    state: DetailSheetState,
    downloadedUrls: Set<String>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (CatalogFile) -> Unit,
    onDownloadAll: (List<CatalogFile>) -> Unit,
    onFavorite: (LiveTitle) -> Unit,
) {
    val title = state.title ?: return
    val sorted = remember(state.files) {
        state.files.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName.lowercase() })
        )
    }
    val seasons = remember(sorted) {
        sorted.mapNotNull { it.season }.distinct().sorted()
    }
    var selectedSeason by remember(title.folderUrl, seasons) {
        mutableStateOf(seasons.firstOrNull())
    }
    var downloadFeedback by remember(title.folderUrl) { mutableStateOf<String?>(null) }
    val visibleFiles = remember(sorted, selectedSeason, seasons) {
        if (seasons.size <= 1 || selectedSeason == null) sorted
        else sorted.filter { it.season == selectedSeason }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LanesPalette.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = LanesSpacing.ScreenHorizontal)
                .padding(bottom = LanesSpacing.Lg),
        ) {
            // Hero header: poster artwork + structured metadata hierarchy
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Base),
            ) {
                LanesPoster(
                    title = title.title,
                    posterUrl = title.posterUrl,
                    targetWidthPx = 420,
                    targetHeightPx = 600,
                    allowCrossfade = true,
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(LanesSizes.PosterAspectRatio)
                        .clip(RoundedCornerShape(LanesRadii.Poster)),
                )

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = title.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = LanesPalette.InkPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onFavorite(title) },
                            modifier = Modifier.size(LanesSizes.MinTouchTarget),
                        ) {
                            Icon(
                                imageVector = if (title.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (title.favorite) "Remove from favorites" else "Save to favorites",
                                tint = if (title.favorite) LanesPalette.Error else LanesPalette.InkSecondary,
                            )
                        }
                    }

                    Text(
                        text = listOfNotNull(
                            title.year?.toString(),
                            title.category,
                            title.sourceName,
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = LanesPalette.InkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        title.bestQuality?.let {
                            LanesBadge(it, containerColor = LanesPalette.AccentContainer, contentColor = LanesPalette.Accent)
                        }
                        title.bestSourceTag?.let { LanesBadge(it) }
                        title.bestCodec?.let { LanesBadge(it) }
                        title.language?.let { LanesBadge(it) }
                        if (title.hasDualAudio) {
                            LanesBadge("Dual Audio", containerColor = LanesPalette.AmberContainer, contentColor = LanesPalette.Amber)
                        }
                    }
                }
            }

            if (title.downloadedCount > 0) {
                Spacer(Modifier.height(10.dp))
                val totalCount = maxOf(title.downloadedCount, state.files.size)
                LanesBadge(
                    text = "${title.downloadedCount} of $totalCount saved in Downloads/LanFetch",
                    containerColor = LanesPalette.AccentContainer,
                    contentColor = LanesPalette.Accent,
                )
            }

            Spacer(Modifier.height(LanesSpacing.Base))

            // Smart CTA label: single movie vs season pack vs all episodes
            val ctaLabel = when {
                state.loading && state.files.isEmpty() -> "Fetching files from server…"
                seasons.size > 1 && selectedSeason != null ->
                    "Download Season $selectedSeason (${visibleFiles.size} episodes)"
                state.files.size == 1 -> {
                    val f = state.files.first()
                    if (f.sizeBytes > 0) "Download (${Format.bytes(f.sizeBytes)})" else "Download file"
                }
                else -> "Download all (${visibleFiles.size} files)"
            }
            Button(
                onClick = {
                    val count = visibleFiles.size
                    downloadFeedback = if (count == 1) {
                        "Download request sent. Check Downloads for progress."
                    } else {
                        "$count files requested. Check Downloads for progress."
                    }
                    onDownloadAll(visibleFiles)
                },
                enabled = visibleFiles.isNotEmpty() && !state.loading,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(LanesRadii.Control),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LanesPalette.Accent,
                    contentColor = LanesPalette.OnAccent,
                ),
            ) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctaLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            downloadFeedback?.let { feedback ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(LanesRadii.Control),
                    color = LanesPalette.AccentContainer,
                ) {
                    Text(
                        text = feedback,
                        style = TabularBodySmall,
                        color = LanesPalette.Accent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            // Multi-season filter bar automatically shown for multi-season TV series
            if (seasons.size > 1) {
                Spacer(Modifier.height(LanesSpacing.Md))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Sm)) {
                    items(seasons, key = { it }) { season ->
                        val active = selectedSeason == season
                        Surface(
                            onClick = { selectedSeason = season },
                            shape = RoundedCornerShape(LanesRadii.Pill),
                            color = if (active) LanesPalette.AccentContainer else LanesPalette.SurfaceElevated,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (active) LanesPalette.Accent else LanesPalette.Rule,
                            ),
                        ) {
                            Text(
                                text = "Season $season",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) LanesPalette.Accent else LanesPalette.InkSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                    item(key = -1) {
                        val active = selectedSeason == null
                        Surface(
                            onClick = { selectedSeason = null },
                            shape = RoundedCornerShape(LanesRadii.Pill),
                            color = if (active) LanesPalette.AccentContainer else LanesPalette.SurfaceElevated,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (active) LanesPalette.Accent else LanesPalette.Rule,
                            ),
                        ) {
                            Text(
                                text = "All (${sorted.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) LanesPalette.Accent else LanesPalette.InkSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(LanesSpacing.Md))
            LanesDivider()

            when {
                state.loading && state.files.isEmpty() -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = LanesPalette.Accent,
                            trackColor = LanesPalette.Rule,
                        )
                        Text(
                            "Reading folder index from ${title.sourceName}…",
                            style = TabularBodySmall,
                            color = LanesPalette.InkSecondary,
                        )
                    }
                }

                !state.loading && state.files.isEmpty() -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            state.error ?: "No downloadable video files found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LanesPalette.InkSecondary,
                        )
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(LanesRadii.Control),
                        ) {
                            Text("Retry")
                        }
                    }
                }

                else -> {
                    if (state.loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = LanesPalette.Accent,
                            trackColor = LanesPalette.Rule,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        contentPadding = PaddingValues(vertical = LanesSpacing.Xs),
                    ) {
                        items(visibleFiles, key = { it.fileUrl }) { file ->
                            val isSaved = file.fileUrl in downloadedUrls
                            FileRow(
                                file = file,
                                saved = isSaved,
                                onDownload = {
                                    downloadFeedback = "Episode request sent. Check Downloads for progress."
                                    onDownload(file)
                                },
                            )
                            LanesDivider(subtle = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: CatalogFile,
    saved: Boolean,
    onDownload: () -> Unit,
) {
    val label = remember(file.season, file.episode, file.fileName) {
        when {
            file.episode != null -> "E%02d • %s".format(file.episode, file.fileName)
            file.season != null -> "S%02d • %s".format(file.season, file.fileName)
            else -> file.fileName
        }
    }
    val specs = remember(file.quality, file.sourceTag, file.codec, file.audio, file.sizeBytes) {
        listOfNotNull(
            file.quality,
            file.sourceTag,
            file.codec,
            file.audio,
            if (file.sizeBytes > 0) Format.bytes(file.sizeBytes) else null,
        ).joinToString(" • ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDownload)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = LanesPalette.InkPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (specs.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = specs,
                    style = TabularBodySmall,
                    color = LanesPalette.InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(LanesSizes.MinTouchTarget),
            contentAlignment = Alignment.Center,
        ) {
            if (saved) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Already saved",
                    tint = LanesPalette.Accent,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = "Download ${file.fileName}",
                    tint = LanesPalette.Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
