package com.lanfetch.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanfetch.app.R
import com.lanfetch.app.data.LiveTitle
import com.lanfetch.app.download.Format
import com.lanfetch.app.ui.brand.LanFetchHeaderBrand
import com.lanfetch.app.ui.components.LanesBadge
import com.lanfetch.app.ui.components.LanesDivider
import com.lanfetch.app.ui.components.LanesEmptyState
import com.lanfetch.app.ui.components.LanesPoster
import com.lanfetch.app.ui.components.LanesScreenHeader
import com.lanfetch.app.ui.theme.TabularBodySmall
import com.lanfetch.app.ui.theme.TabularLabelSmall
import com.lanfetch.app.ui.theme.LanesMotion
import com.lanfetch.app.ui.theme.LanesPalette
import com.lanfetch.app.ui.theme.LanesRadii
import com.lanfetch.app.ui.theme.LanesSizes
import com.lanfetch.app.ui.theme.LanesSpacing

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onSettings: () -> Unit = {}) {
    val searchQuery by vm.searchQuery.collectAsState()
    val activeSearchQuery by vm.activeSearchQuery.collectAsState()
    val categories by vm.categories.collectAsState()
    val discoveringCategories by vm.discoveringCategories.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()
    val qualityChoice by vm.qualityChoice.collectAsState()
    val filter by vm.filter.collectAsState()
    val facets by vm.facets.collectAsState()
    val items by vm.items.collectAsState()
    val isLoadingInitial by vm.isLoadingInitial.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val isLoadingMore by vm.isLoadingMore.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val statusText by vm.statusText.collectAsState()
    val downloadedUrls by vm.downloadedUrls.collectAsState()
    val detailState by vm.detailSheet.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }

    val keyboard = LocalSoftwareKeyboardController.current
    val tabScrollKey = remember(selectedCategory, activeSearchQuery, filter) {
        "${selectedCategory ?: "__ALL__"}|$activeSearchQuery|${filter.hashCode()}"
    }
    val gridState = remember(tabScrollKey) { LazyGridState() }

    val currentHasMore by rememberUpdatedState(hasMore)
    val currentLoadingMore by rememberUpdatedState(isLoadingMore)
    val currentLoadingInitial by rememberUpdatedState(isLoadingInitial)

    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 4 &&
                currentHasMore && !currentLoadingMore && !currentLoadingInitial
            ) {
                vm.loadMore()
            }
        }
    }

    LaunchedEffect(tabScrollKey, items.size, hasMore, isLoadingMore, isLoadingInitial) {
        val info = gridState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 2 &&
            hasMore && !isLoadingMore && !isLoadingInitial
        ) {
            vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize().background(LanesPalette.Canvas)) {
                LanesScreenHeader(
                    title = { LanFetchHeaderBrand() },
                    subtitle = statusText,
                    actions = {
                        IconButton(
                            onClick = onSettings,
                            modifier = Modifier.size(LanesSizes.MinTouchTarget),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = LanesPalette.InkSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )

                // Slim unified search bar + inline filter button to maximize above-the-fold poster area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactSearchInput(
                        query = searchQuery,
                        onQueryChange = vm::setQuery,
                        onSubmit = {
                            vm.submitSearch()
                            keyboard?.hide()
                        },
                        onClear = { vm.setQuery("") },
                        modifier = Modifier.weight(1f),
                    )
                    BadgedBox(
                        badge = {
                            if (filter.activeCount > 0) {
                                Badge(containerColor = LanesPalette.Accent, contentColor = LanesPalette.OnAccent) {
                                    Text("${filter.activeCount}")
                                }
                            }
                        },
                    ) {
                        Surface(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(LanesRadii.Control),
                            color = if (filter.activeCount > 0) LanesPalette.AccentContainer
                            else LanesPalette.SurfaceElevated,
                            border = BorderStroke(
                                1.dp,
                                if (filter.activeCount > 0) LanesPalette.Accent
                                else LanesPalette.Rule,
                            ),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_filter_list),
                                    contentDescription = "Filters and sorting",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (filter.activeCount > 0) LanesPalette.Accent
                                    else LanesPalette.InkSecondary,
                                )
                            }
                        }
                    }
                }

                // Editorial category navigation strip with subtle underline indicator
                LazyRow(
                    contentPadding = PaddingValues(horizontal = LanesSpacing.ScreenHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item(key = "__ALL__") {
                        CategoryTab(
                            selected = activeSearchQuery.isEmpty() && selectedCategory == null,
                            label = "All",
                            onClick = { vm.selectCategory(null) },
                        )
                    }
                    item(key = LibraryViewModel.FAVORITES_CATEGORY) {
                        CategoryTab(
                            selected = activeSearchQuery.isEmpty() &&
                                selectedCategory == LibraryViewModel.FAVORITES_CATEGORY,
                            label = "Favorites",
                            onClick = { vm.selectCategory(LibraryViewModel.FAVORITES_CATEGORY) },
                        )
                    }
                    items(categories, key = { it.name }) { cat ->
                        CategoryTab(
                            selected = activeSearchQuery.isEmpty() && selectedCategory == cat.name,
                            label = cat.name,
                            onClick = { vm.selectCategory(cat.name) },
                        )
                    }
                }

                LanesDivider()

                AnimatedVisibility(
                    visible = isRefreshing,
                    enter = fadeIn(tween(LanesMotion.FastMs)),
                    exit = fadeOut(tween(LanesMotion.FastMs)),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = LanesPalette.Accent,
                        trackColor = LanesPalette.RuleSubtle,
                    )
                }

                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.weight(1f),
                ) {
                when {
                    isLoadingInitial -> {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = LanesPalette.Accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = statusText,
                                    style = TabularBodySmall,
                                    color = LanesPalette.InkSecondary,
                                )
                            }
                        }
                    }

                    items.isEmpty() && !errorMessage.isNullOrBlank() -> {
                        LanesEmptyState(
                            painter = painterResource(R.drawable.ic_error),
                            title = "Servers unreachable",
                            subtitle = errorMessage ?: "Check LAN Wi-Fi connection and enabled servers.",
                            action = {
                                OutlinedButton(
                                    onClick = { vm.refresh() },
                                    shape = RoundedCornerShape(LanesRadii.Control),
                                ) {
                                    Text("Retry")
                                }
                            },
                        )
                    }

                    items.isEmpty() -> {
                        val hasActiveQuery = filter.activeCount > 0 || searchQuery.isNotEmpty() || selectedCategory != null
                        LanesEmptyState(
                            painter = painterResource(R.drawable.ic_movie),
                            title = when {
                                activeSearchQuery.isNotEmpty() -> "No matches found"
                                selectedCategory == LibraryViewModel.FAVORITES_CATEGORY -> "No saved favorites"
                                else -> "No titles available"
                            },
                            subtitle = when {
                                activeSearchQuery.isNotEmpty() -> "Try another spelling or adjust your active filters."
                                selectedCategory == LibraryViewModel.FAVORITES_CATEGORY ->
                                    "Tap the heart on any title to save it here for quick access."
                                else -> "Switch category, clear filters, or refresh the server directory."
                            },
                            action = if (hasActiveQuery && selectedCategory != LibraryViewModel.FAVORITES_CATEGORY) {
                                {
                                    OutlinedButton(
                                        onClick = { vm.clearFilters() },
                                        shape = RoundedCornerShape(LanesRadii.Control),
                                    ) {
                                        Text("Reset filters")
                                    }
                                }
                            } else null,
                        )
                    }

                    else -> {
                        key(tabScrollKey) {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = 152.dp),
                                contentPadding = PaddingValues(
                                    start = LanesSpacing.ScreenHorizontal,
                                    end = LanesSpacing.ScreenHorizontal,
                                    top = LanesSpacing.Md,
                                    bottom = LanesSpacing.BottomListBreath,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Md),
                                verticalArrangement = Arrangement.spacedBy(LanesSpacing.Lg),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(
                                    items = items,
                                    key = { it.folderUrl },
                                    contentType = { "poster_card" },
                                ) { title ->
                                    PosterGridItem(
                                        title = title,
                                        onOpen = { vm.selectTitle(title) },
                                        onDownload = { vm.downloadTitle(title) },
                                        onFavorite = { vm.toggleFavorite(title) },
                                    )
                                }

                                if (isLoadingMore) {
                                    item(key = "__LOADING_MORE__", span = { GridItemSpan(maxLineSpan) }) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(LanesSpacing.Base),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = LanesPalette.Accent,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                "Loading next page…",
                                                style = TabularBodySmall,
                                                color = LanesPalette.InkSecondary,
                                            )
                                        }
                                    }
                                } else if (hasMore) {
                                    item(key = "__LOAD_MORE_BTN__", span = { GridItemSpan(maxLineSpan) }) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            OutlinedButton(
                                                onClick = { vm.loadMore() },
                                                shape = RoundedCornerShape(LanesRadii.Control),
                                            ) {
                                                Text("Load more titles")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }

    if (showFilterSheet) {
        LibraryFilterSheet(
            filter = filter,
            facets = facets,
            onChange = vm::setFilter,
            onClear = vm::clearFilters,
            onDismiss = { showFilterSheet = false },
        )
    }

    // Audit B12: choose a file size/quality before queueing a multi-file title.
    qualityChoice?.let { request ->
        AlertDialog(
            onDismissRequest = vm::dismissQualityChoice,
            title = { Text("Download ${request.title.title}") },
            text = {
                Column {
                    Text("This title has ${request.files.size} files. Pick one:")
                    Spacer(Modifier.height(12.dp))
                    LibraryViewModel.QualityPick.entries.forEach { pick ->
                        val file = vm.pickFileFor(request.files, pick)
                        TextButton(onClick = { vm.chooseQuality(pick) }) {
                            Text(
                                "${pick.name.lowercase().replaceFirstChar { it.uppercase() }} — " +
                                    "${file.quality ?: "Unknown quality"} · ${Format.bytes(file.sizeBytes)}",
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = vm::dismissQualityChoice) { Text("Cancel") }
            },
        )
    }

    if (detailState.title != null) {
        MediaDetailSheet(
            state = detailState,
            downloadedUrls = downloadedUrls,
            onDismiss = vm::closeTitle,
            onRetry = vm::retrySelectedFiles,
            onDownload = { file -> detailState.title?.let { vm.download(file, it.title) } },
            onDownloadAll = { files -> detailState.title?.let { vm.downloadAll(files, it.title) } },
            onFavorite = vm::toggleFavorite,
        )
    }
}

@Composable
private fun CompactSearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(LanesRadii.Control))
            .background(LanesPalette.SurfaceElevated)
            .border(1.dp, LanesPalette.Rule, RoundedCornerShape(LanesRadii.Control))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = LanesPalette.InkMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = "Search movies, series, Hindi, 4K…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LanesPalette.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LanesPalette.InkPrimary),
                cursorBrush = SolidColor(LanesPalette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Search LAN media servers"
                },
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "Clear search",
                    tint = LanesPalette.InkSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) LanesPalette.InkPrimary else LanesPalette.InkSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(7.dp))
        Box(
            modifier = Modifier
                .width(if (selected) 24.dp else 0.dp)
                .height(2.dp)
                .background(if (selected) LanesPalette.Accent else LanesPalette.Canvas),
        )
    }
}

@Composable
private fun PosterGridItem(
    title: LiveTitle,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
) {
    val metaLine = remember(
        title.year, title.kind, title.seasonCount, title.maxSizeBytes,
    ) {
        listOfNotNull(
            title.year?.toString(),
            if (title.kind == "series" && title.seasonCount > 0) {
                if (title.seasonCount == 1) "1S" else "${title.seasonCount}S"
            } else null,
            if (title.maxSizeBytes > 0) Format.bytes(title.maxSizeBytes) else null,
        ).joinToString(" • ").ifEmpty { if (title.kind == "series") "Series" else "Media" }
    }
    val bottomBadge = remember(title.seasonCount, title.fileCount) {
        when {
            title.seasonCount > 1 -> "${title.seasonCount}S"
            title.seasonCount == 1 -> "1S"
            title.fileCount > 1 -> "${title.fileCount} files"
            else -> null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(LanesSizes.PosterAspectRatio)
                .clip(RoundedCornerShape(LanesRadii.Poster))
                .border(0.75.dp, LanesPalette.RuleSubtle, RoundedCornerShape(LanesRadii.Poster)),
        ) {
            LanesPoster(
                title = title.title,
                posterUrl = title.posterUrl,
                targetWidthPx = 360,
                targetHeightPx = 520,
                allowCrossfade = false,
                modifier = Modifier.fillMaxSize(),
            )

            if (title.downloadedCount > 0) {
                LanesBadge(
                    text = if (title.fileCount > 0 && title.downloadedCount >= title.fileCount) "Saved"
                    else if (title.fileCount > 0) "${title.downloadedCount}/${title.fileCount}"
                    else "${title.downloadedCount} saved",
                    containerColor = LanesPalette.Accent,
                    contentColor = LanesPalette.OnAccent,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }

            if (title.bestQuality != null) {
                LanesBadge(
                    text = title.bestQuality,
                    containerColor = LanesPalette.Scrim,
                    contentColor = LanesPalette.InkPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }

            if (bottomBadge != null) {
                LanesBadge(
                    text = bottomBadge,
                    containerColor = LanesPalette.Scrim,
                    contentColor = LanesPalette.InkPrimary,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = title.category,
            style = MaterialTheme.typography.labelSmall,
            color = LanesPalette.AccentDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = title.title,
            style = MaterialTheme.typography.titleSmall,
            color = LanesPalette.InkPrimary,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = metaLine,
                style = TabularLabelSmall,
                color = LanesPalette.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onFavorite,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (title.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (title.favorite) "Remove favorite" else "Add favorite",
                    tint = if (title.favorite) LanesPalette.Error else LanesPalette.InkMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = "Download ${title.title}",
                    tint = LanesPalette.Accent,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}
