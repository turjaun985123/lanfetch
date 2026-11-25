package com.lanfetch.app.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.painterResource
import com.lanfetch.app.R
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.CatalogTitle
import com.lanfetch.app.data.Facets
import com.lanfetch.app.download.Format
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.SortBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel) {
    val searchQuery by vm.searchQuery.collectAsState()
    val filter by vm.filter.collectAsState()
    val facets by vm.facets.collectAsState()
    val counts by vm.counts.collectAsState()
    val downloadedUrls by vm.downloadedUrls.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val titles = vm.titles.collectAsLazyPagingItems()

    var showFilterSheet by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    val selectedTitle by vm.selectedTitle.collectAsState()
    val selectedFiles by vm.selectedFiles.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search movies & shows…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter.kinds.isEmpty(),
                onClick = { vm.setFilter(filter.copy(kinds = emptySet())) },
                label = { Text("All") },
            )
            FilterChip(
                selected = filter.kinds == setOf("movie"),
                onClick = { vm.setFilter(filter.copy(kinds = setOf("movie"))) },
                label = { Text("Movies") },
            )
            FilterChip(
                selected = filter.kinds == setOf("series"),
                onClick = { vm.setFilter(filter.copy(kinds = setOf("series"))) },
                label = { Text("Shows") },
            )
            Spacer(Modifier.weight(1f))
            BadgedBox(badge = {
                if (filter.activeCount > 0) Badge { Text("${filter.activeCount}") }
            }) {
                IconButton(onClick = { showFilterSheet = true }) {
                    Icon(painterResource(R.drawable.ic_filter_list), contentDescription = "Filters")
                }
            }
            IconButton(onClick = { vm.syncAll() }, enabled = syncing.isEmpty()) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = "Sync all sources")
            }
        }

        Text(
            when {
                syncing.isNotEmpty() -> {
                    val progress = syncing.values.first()
                    "Syncing… ${progress.foldersScanned} folders, ${progress.itemsFound} files"
                }
                counts.titles == 0 -> "No catalog yet — sync a server to discover downloadable files."
                else -> "${counts.titles} titles • ${counts.files} files"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        when {
            titles.loadState.refresh is LoadState.Loading && titles.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            titles.loadState.refresh is LoadState.Error && titles.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not load the catalog.", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { titles.retry() }) { Text("Retry") }
                    }
                }
            }

            titles.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(R.drawable.ic_movie), contentDescription = null,
                            Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (counts.titles == 0) "Nothing here yet.\nSync a server from the Servers tab."
                            else "No titles match your filters.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (filter.activeCount > 0 || searchQuery.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.clearFilters() }) { Text("Clear filters") }
                        }
                    }
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 158.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = titles.itemCount,
                        key = titles.itemKey { it.id },
                    ) { index ->
                        val title = titles[index] ?: return@items
                        TitleCard(
                            title = title,
                            onClick = {
                                vm.selectTitle(title)
                                showGroup = true
                            },
                            onDownload = { vm.downloadTitle(title) },
                            onFavorite = { vm.toggleFavorite(title) },
                        )
                    }

                    if (titles.loadState.append is LoadState.Loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            filter = filter,
            facets = facets,
            onChange = { vm.setFilter(it) },
            onClear = { vm.clearFilters() },
            onDismiss = { showFilterSheet = false },
        )
    }

    if (showGroup) {
        val title = selectedTitle
        if (title == null) {
            showGroup = false
        } else {
            GroupDetailSheet(
                title = title,
                files = selectedFiles,
                downloadedUrls = downloadedUrls,
                onDismiss = {
                    showGroup = false
                    vm.closeTitle()
                },
                onDownload = { vm.download(it, title.title) },
                onDownloadAll = {
                    vm.downloadAll(selectedFiles, title.title)
                    showGroup = false
                    vm.closeTitle()
                },
                onFavorite = { vm.toggleFavorite(title) },
            )
        }
    }
}

@Composable
private fun TitleCard(
    title: CatalogTitle,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(titleColor(title.title)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.title.split(" ").filter { it.isNotBlank() }.take(2)
                        .map { it.first().uppercase() }.joinToString(""),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                )
                if (title.posterUrl != null) {
                    coil.compose.AsyncImage(
                        model = title.posterUrl,
                        contentDescription = title.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (title.downloadedCount > 0) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (title.downloadedCount >= title.fileCount) "Saved"
                            else "${title.downloadedCount}/${title.fileCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (title.bestQuality != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            title.bestQuality,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
                if (title.fileCount > 1) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${title.fileCount} files",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    title.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        title.year?.toString(),
                        title.bestSourceTag,
                        title.bestCodec,
                        if (title.maxSizeBytes > 0) Format.bytes(title.maxSizeBytes) else null,
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    title.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (title.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (title.favorite) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            contentDescription = "Download",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filter: CatalogFilter,
    facets: Facets,
    onChange: (CatalogFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onClear) { Text("Clear all") }
            }

            Text("Sort by", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortBy.entries.forEach { sort ->
                    FilterChip(
                        selected = filter.sort == sort,
                        onClick = { onChange(filter.copy(sort = sort)) },
                        label = {
                            Text(sort.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }

            if (facets.qualities.isNotEmpty()) {
                Text("Quality", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.qualities.forEach { quality ->
                        FilterChip(
                            selected = quality in filter.qualities,
                            onClick = { onChange(filter.copy(qualities = filter.qualities.toggle(quality))) },
                            label = { Text(quality) },
                        )
                    }
                }
            }

            if (facets.categories.isNotEmpty()) {
                Text("Category", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.categories.forEach { category ->
                        FilterChip(
                            selected = category in filter.categories,
                            onClick = { onChange(filter.copy(categories = filter.categories.toggle(category))) },
                            label = { Text(category) },
                        )
                    }
                }
            }

            if (facets.sourceNames.size > 1) {
                Text("Server", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.sourceNames.forEach { source ->
                        FilterChip(
                            selected = source in filter.sourceNames,
                            onClick = { onChange(filter.copy(sourceNames = filter.sourceNames.toggle(source))) },
                            label = { Text(source) },
                        )
                    }
                }
            }

            if (facets.languages.isNotEmpty()) {
                Text("Language", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.languages.forEach { language ->
                        FilterChip(
                            selected = language in filter.languages,
                            onClick = { onChange(filter.copy(languages = filter.languages.toggle(language))) },
                            label = { Text(language) },
                        )
                    }
                }
            }

            if (facets.sourceTags.isNotEmpty()) {
                Text("Release type", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.sourceTags.forEach { tag ->
                        FilterChip(
                            selected = tag in filter.sourceTags,
                            onClick = { onChange(filter.copy(sourceTags = filter.sourceTags.toggle(tag))) },
                            label = { Text(tag) },
                        )
                    }
                }
            }

            if (facets.years.size > 1) {
                val minYear = facets.years.first().toFloat()
                val maxYear = facets.years.last().toFloat()
                var range by remember(filter.yearMin, filter.yearMax, minYear, maxYear) {
                    mutableStateOf(
                        (filter.yearMin?.toFloat() ?: minYear)..(filter.yearMax?.toFloat() ?: maxYear)
                    )
                }
                Text(
                    "Year: ${range.start.toInt()} – ${range.endInclusive.toInt()}",
                    style = MaterialTheme.typography.titleSmall,
                )
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    onValueChangeFinished = {
                        onChange(
                            filter.copy(
                                yearMin = range.start.toInt(),
                                yearMax = range.endInclusive.toInt(),
                            )
                        )
                    },
                    valueRange = minYear..maxYear,
                )
            }

            Text("Minimum size", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sizes = listOf(
                    null to "Any",
                    700_000_000L to "700 MB+",
                    1_500_000_000L to "1.5 GB+",
                    4_000_000_000L to "4 GB+",
                )
                sizes.forEach { (bytes, label) ->
                    FilterChip(
                        selected = filter.minSizeBytes == bytes,
                        onClick = { onChange(filter.copy(minSizeBytes = bytes)) },
                        label = { Text(label) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favorites only", Modifier.weight(1f))
                Switch(
                    checked = filter.favoritesOnly,
                    onCheckedChange = { onChange(filter.copy(favoritesOnly = it)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dual / multi audio only", Modifier.weight(1f))
                Switch(
                    checked = filter.dualAudioOnly,
                    onCheckedChange = { onChange(filter.copy(dualAudioOnly = it)) },
                )
            }

            Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Show results") }
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailSheet(
    title: CatalogTitle,
    files: List<CatalogFile>,
    downloadedUrls: Set<String>,
    onDismiss: () -> Unit,
    onDownload: (CatalogFile) -> Unit,
    onDownloadAll: () -> Unit,
    onFavorite: () -> Unit,
) {
    val sorted = remember(files) {
        files.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName.lowercase() })
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title.posterUrl != null) {
                    coil.compose.AsyncImage(
                        model = title.posterUrl,
                        contentDescription = title.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            title.year?.toString(), title.category, title.language, title.sourceName,
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (title.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (title.favorite) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (title.downloadedCount > 0) {
                Text(
                    "${title.downloadedCount} of ${title.fileCount} files downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }
            Button(onClick = onDownloadAll, Modifier.fillMaxWidth(), enabled = files.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download all (${files.size} files)")
            }
            Spacer(Modifier.height(12.dp))
            if (files.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sorted, key = { it.id }) { file ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (file.episode != null) {
                                            "S${file.season ?: "?"} E${file.episode} — ${file.fileName}"
                                        } else {
                                            file.fileName
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOfNotNull(
                                            file.quality, file.sourceTag, file.codec, file.audio,
                                            if (file.sizeBytes > 0) Format.bytes(file.sizeBytes) else null,
                                        ).joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val isSaved = file.fileUrl in downloadedUrls
                                IconButton(onClick = { onDownload(file) }) {
                                    if (isSaved) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "Already downloaded",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Icon(
                                            painterResource(R.drawable.ic_download),
                                            contentDescription = "Download this file",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
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
