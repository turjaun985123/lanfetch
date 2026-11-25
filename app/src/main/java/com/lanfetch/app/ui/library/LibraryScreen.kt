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
import com.lanfetch.app.ui.brand.TurjaunHeaderBrand
import com.lanfetch.app.ui.components.TurjaunBadge
import com.lanfetch.app.ui.components.TurjaunDivider
import com.lanfetch.app.ui.components.TurjaunEmptyState
import com.lanfetch.app.ui.components.TurjaunPoster
import com.lanfetch.app.ui.components.TurjaunScreenHeader
import com.lanfetch.app.ui.theme.TabularBodySmall
import com.lanfetch.app.ui.theme.TabularLabelSmall
import com.lanfetch.app.ui.theme.TurjaunMotion
import com.lanfetch.app.ui.theme.TurjaunPalette
import com.lanfetch.app.ui.theme.TurjaunRadii
import com.lanfetch.app.ui.theme.TurjaunSizes
import com.lanfetch.app.ui.theme.TurjaunSpacing

@Composable
fun LibraryScreen(vm: LibraryViewModel) {
    val searchQuery by vm.searchQuery.collectAsState()
    val activeSearchQuery by vm.activeSearchQuery.collectAsState()
    val categories by vm.categories.collectAsState()
    val discoveringCategories by vm.discoveringCategories.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()
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

    Column(Modifier.fillMaxSize().background(TurjaunPalette.Canvas)) {
                TurjaunScreenHeader(
                    title = { TurjaunHeaderBrand() },
                    subtitle = statusText,
                    actions = {
                        Surface(
                            onClick = { showFilterSheet = true },
                            shape = RoundedCornerShape(TurjaunRadii.Control),
                            color = TurjaunPalette.SurfaceElevated,
                            border = BorderStroke(0.5.dp, TurjaunPalette.Rule),
                        ) {
                            Text(
                                text = sortLabel(filter.sort),
                                style = MaterialTheme.typography.labelSmall,
                                color = TurjaunPalette.InkSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        IconButton(
                            onClick = { vm.refresh() },
                            modifier = Modifier.size(TurjaunSizes.MinTouchTarget),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh live catalog",
                                tint = TurjaunPalette.InkSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )

                // Slim unified search bar + inline filter button to maximize above-the-fold poster area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = 8.dp),
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
                                Badge(containerColor = TurjaunPalette.Accent, contentColor = TurjaunPalette.OnAccent) {
                                    Text("${filter.activeCount}")
                                }
                            }
                        },
                    ) {
                        Surface(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(TurjaunRadii.Control),
                            color = if (filter.activeCount > 0) TurjaunPalette.AccentContainer
                            else TurjaunPalette.SurfaceElevated,
                            border = BorderStroke(
                                1.dp,
                                if (filter.activeCount > 0) TurjaunPalette.Accent
                                else TurjaunPalette.Rule,
                            ),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_filter_list),
                                    contentDescription = "Filters and sorting",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (filter.activeCount > 0) TurjaunPalette.Accent
                                    else TurjaunPalette.InkSecondary,
                                )
                            }
                        }
                    }
                }

                // Editorial category navigation strip with subtle underline indicator
                LazyRow(
                    contentPadding = PaddingValues(horizontal = TurjaunSpacing.ScreenHorizontal),
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

                TurjaunDivider()

                AnimatedVisibility(
                    visible = isRefreshing,
                    enter = fadeIn(tween(TurjaunMotion.FastMs)),
                    exit = fadeOut(tween(TurjaunMotion.FastMs)),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = TurjaunPalette.Accent,
                        trackColor = TurjaunPalette.RuleSubtle,
                    )
                }

                when {
                    isLoadingInitial -> {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = TurjaunPalette.Accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = statusText,
                                    style = TabularBodySmall,
                                    color = TurjaunPalette.InkSecondary,
                                )
                            }
                        }
                    }

                    items.isEmpty() && !errorMessage.isNullOrBlank() -> {
                        TurjaunEmptyState(
                            painter = painterResource(R.drawable.ic_error),
                            title = "Servers unreachable",
                            subtitle = errorMessage ?: "Check LAN Wi-Fi connection and enabled servers.",
                            action = {
                                OutlinedButton(
                                    onClick = { vm.refresh() },
                                    shape = RoundedCornerShape(TurjaunRadii.Control),
                                ) {
                                    Text("Retry")
                                }
                            },
                        )
                    }

                    items.isEmpty() -> {
                        val hasActiveQuery = filter.activeCount > 0 || searchQuery.isNotEmpty() || selectedCategory != null
                        TurjaunEmptyState(
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
                                        shape = RoundedCornerShape(TurjaunRadii.Control),
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
                                    start = TurjaunSpacing.ScreenHorizontal,
                                    end = TurjaunSpacing.ScreenHorizontal,
                                    top = TurjaunSpacing.Md,
                                    bottom = TurjaunSpacing.BottomListBreath,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(TurjaunSpacing.Md),
                                verticalArrangement = Arrangement.spacedBy(TurjaunSpacing.Lg),
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
                                            modifier = Modifier.fillMaxWidth().padding(TurjaunSpacing.Base),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = TurjaunPalette.Accent,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                "Loading next page…",
                                                style = TabularBodySmall,
                                                color = TurjaunPalette.InkSecondary,
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
                                                shape = RoundedCornerShape(TurjaunRadii.Control),
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

    if (showFilterSheet) {
        LibraryFilterSheet(
            filter = filter,
            facets = facets,
            onChange = vm::setFilter,
            onClear = vm::clearFilters,
            onDismiss = { showFilterSheet = false },
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
            .clip(RoundedCornerShape(TurjaunRadii.Control))
            .background(TurjaunPalette.SurfaceElevated)
            .border(1.dp, TurjaunPalette.Rule, RoundedCornerShape(TurjaunRadii.Control))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = TurjaunPalette.InkMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = "Search movies, series, Hindi, 4K…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TurjaunPalette.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TurjaunPalette.InkPrimary),
                cursorBrush = SolidColor(TurjaunPalette.Accent),
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
                    tint = TurjaunPalette.InkSecondary,
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
            color = if (selected) TurjaunPalette.InkPrimary else TurjaunPalette.InkSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(7.dp))
        Box(
            modifier = Modifier
                .width(if (selected) 24.dp else 0.dp)
                .height(2.dp)
                .background(if (selected) TurjaunPalette.Accent else TurjaunPalette.Canvas),
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
                .aspectRatio(TurjaunSizes.PosterAspectRatio)
                .clip(RoundedCornerShape(TurjaunRadii.Poster))
                .border(0.75.dp, TurjaunPalette.RuleSubtle, RoundedCornerShape(TurjaunRadii.Poster)),
        ) {
            TurjaunPoster(
                title = title.title,
                posterUrl = title.posterUrl,
                targetWidthPx = 360,
                targetHeightPx = 520,
                allowCrossfade = false,
                modifier = Modifier.fillMaxSize(),
            )

            if (title.downloadedCount > 0) {
                TurjaunBadge(
                    text = if (title.fileCount > 0 && title.downloadedCount >= title.fileCount) "Saved"
                    else if (title.fileCount > 0) "${title.downloadedCount}/${title.fileCount}"
                    else "${title.downloadedCount} saved",
                    containerColor = TurjaunPalette.Accent,
                    contentColor = TurjaunPalette.OnAccent,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }

            if (title.bestQuality != null) {
                TurjaunBadge(
                    text = title.bestQuality,
                    containerColor = TurjaunPalette.Scrim,
                    contentColor = TurjaunPalette.InkPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }

            if (bottomBadge != null) {
                TurjaunBadge(
                    text = bottomBadge,
                    containerColor = TurjaunPalette.Scrim,
                    contentColor = TurjaunPalette.InkPrimary,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = title.category,
            style = MaterialTheme.typography.labelSmall,
            color = TurjaunPalette.AccentDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = title.title,
            style = MaterialTheme.typography.titleSmall,
            color = TurjaunPalette.InkPrimary,
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
                color = TurjaunPalette.InkSecondary,
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
                    tint = if (title.favorite) TurjaunPalette.Error else TurjaunPalette.InkMuted,
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
                    tint = TurjaunPalette.Accent,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}
