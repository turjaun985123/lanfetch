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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.lanfetch.app.data.CatalogItem
import com.lanfetch.app.download.Format
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.FilterEngine
import com.lanfetch.app.filter.SortBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel) {
    val filtered by vm.filtered.collectAsState()
    val groups by vm.groups.collectAsState()
    val filter by vm.filter.collectAsState()
    val facets by vm.facets.collectAsState()
    val allItems by vm.items.collectAsState()
    val syncing by vm.syncing.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<List<CatalogItem>?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Search
        OutlinedTextField(
            value = filter.query,
            onValueChange = { vm.setFilter(filter.copy(query = it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search movies & shows…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setFilter(filter.copy(query = "")) }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )

        // Quick bar
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
                selected = filter.kinds == setOf("episode"),
                onClick = { vm.setFilter(filter.copy(kinds = setOf("episode"))) },
                label = { Text("Shows") },
            )
            Spacer(Modifier.weight(1f))
            BadgedBox(badge = {
                if (filter.activeCount > 0) Badge { Text("${filter.activeCount}") }
            }) {
                IconButton(onClick = { showFilterSheet = true }) {
                    Icon(Icons.Filled.FilterList, contentDescription = "Filters")
                }
            }
            IconButton(onClick = { vm.syncAll() }, enabled = syncing.isEmpty()) {
                Icon(Icons.Filled.Sync, contentDescription = "Sync all sources")
            }
        }

        Text(
            when {
                syncing.isNotEmpty() -> {
                    val p = syncing.values.first()
                    "Syncing… ${p.foldersScanned} folders, ${p.itemsFound} files"
                }
                allItems.isEmpty() -> "No catalog yet — sync a source to discover downloadable files."
                else -> "${groups.size} titles • ${filtered.size} files"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Movie, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (allItems.isEmpty()) "Nothing here yet.\nSync a source from the Sources tab."
                        else "No titles match your filters.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (filter.activeCount > 0 || filter.query.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.clearFilter() }) { Text("Clear filters") }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 158.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(groups, key = { it.first.folderUrl }) { (rep, group) ->
                    TitleCard(
                        rep = rep,
                        fileCount = group.size,
                        onClick = { selectedGroup = group },
                        onDownload = { vm.download(rep) },
                        onFavorite = { vm.toggleFavorite(rep) },
                    )
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            filter = filter,
            facets = facets,
            onChange = { vm.setFilter(it) },
            onClear = { vm.clearFilter() },
            onDismiss = { showFilterSheet = false },
        )
    }

    val group = selectedGroup
    if (group != null) {
        GroupDetailSheet(
            group = group,
            onDismiss = { selectedGroup = null },
            onDownload = { vm.download(it) },
            onDownloadAll = { vm.downloadGroup(group); selectedGroup = null },
            onFavorite = { vm.toggleFavorite(it) },
        )
    }
}

@Composable
private fun TitleCard(
    rep: CatalogItem,
    fileCount: Int,
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
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(titleColor(rep.title)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    rep.title.split(" ").filter { it.isNotBlank() }.take(2)
                        .map { it.first().uppercase() }.joinToString(""),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                )
                if (rep.posterUrl != null) {
                    coil.compose.AsyncImage(
                        model = rep.posterUrl,
                        contentDescription = rep.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (rep.quality != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(rep.quality, style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
                if (fileCount > 1) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("$fileCount files", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    rep.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        rep.year?.toString(),
                        rep.sourceTag,
                        rep.codec,
                        if (rep.sizeBytes > 0) Format.bytes(rep.sizeBytes) else null,
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    rep.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (rep.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (rep.favorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
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
    facets: FilterEngine.Facets,
    onChange: (CatalogFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onClear) { Text("Clear all") }
            }

            Text("Sort by", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortBy.entries.forEach { s ->
                    FilterChip(
                        selected = filter.sort == s,
                        onClick = { onChange(filter.copy(sort = s)) },
                        label = { Text(s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            if (facets.qualities.isNotEmpty()) {
                Text("Quality", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.qualities.forEach { q ->
                        FilterChip(
                            selected = q in filter.qualities,
                            onClick = { onChange(filter.copy(qualities = filter.qualities.toggle(q))) },
                            label = { Text(q) },
                        )
                    }
                }
            }

            if (facets.categories.isNotEmpty()) {
                Text("Category", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.categories.forEach { c ->
                        FilterChip(
                            selected = c in filter.categories,
                            onClick = { onChange(filter.copy(categories = filter.categories.toggle(c))) },
                            label = { Text(c) },
                        )
                    }
                }
            }

            if (facets.sourceNames.size > 1) {
                Text("Server", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.sourceNames.forEach { s ->
                        FilterChip(
                            selected = s in filter.sourceNames,
                            onClick = { onChange(filter.copy(sourceNames = filter.sourceNames.toggle(s))) },
                            label = { Text(s) },
                        )
                    }
                }
            }

            if (facets.languages.isNotEmpty()) {
                Text("Language", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.languages.forEach { l ->
                        FilterChip(
                            selected = l in filter.languages,
                            onClick = { onChange(filter.copy(languages = filter.languages.toggle(l))) },
                            label = { Text(l) },
                        )
                    }
                }
            }

            if (facets.sourceTags.isNotEmpty()) {
                Text("Release type", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facets.sourceTags.forEach { t ->
                        FilterChip(
                            selected = t in filter.sourceTags,
                            onClick = { onChange(filter.copy(sourceTags = filter.sourceTags.toggle(t))) },
                            label = { Text(t) },
                        )
                    }
                }
            }

            if (facets.years.size > 1) {
                val minY = facets.years.first().toFloat()
                val maxY = facets.years.last().toFloat()
                var range by remember(filter.yearMin, filter.yearMax, minY, maxY) {
                    mutableStateOf((filter.yearMin?.toFloat() ?: minY)..(filter.yearMax?.toFloat() ?: maxY))
                }
                Text("Year: ${range.start.toInt()} – ${range.endInclusive.toInt()}", style = MaterialTheme.typography.titleSmall)
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    onValueChangeFinished = {
                        onChange(filter.copy(yearMin = range.start.toInt(), yearMax = range.endInclusive.toInt()))
                    },
                    valueRange = minY..maxY,
                )
            }

            Text("Minimum size", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sizes = listOf(null to "Any", 700_000_000L to "700 MB+", 1_500_000_000L to "1.5 GB+", 4_000_000_000L to "4 GB+")
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
                Switch(checked = filter.favoritesOnly, onCheckedChange = { onChange(filter.copy(favoritesOnly = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dual / multi audio only", Modifier.weight(1f))
                Switch(checked = filter.dualAudioOnly, onCheckedChange = { onChange(filter.copy(dualAudioOnly = it)) })
            }

            Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Show results") }
        }
    }
}

private fun Set<String>.toggle(v: String): Set<String> = if (v in this) this - v else this + v

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailSheet(
    group: List<CatalogItem>,
    onDismiss: () -> Unit,
    onDownload: (CatalogItem) -> Unit,
    onDownloadAll: () -> Unit,
    onFavorite: (CatalogItem) -> Unit,
) {
    val rep = group.maxByOrNull { it.sizeBytes }!!
    val sorted = remember(group) {
        group.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName.lowercase() }))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rep.posterUrl != null) {
                    coil.compose.AsyncImage(
                        model = rep.posterUrl,
                        contentDescription = rep.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(rep.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(rep.year?.toString(), rep.category, rep.language, rep.sourceName).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onFavorite(rep) }) {
                    Icon(
                        if (rep.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (rep.favorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDownloadAll, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download all (${group.size} files)")
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sorted, key = { it.id }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (item.episode != null) "S${item.season ?: "?"} E${item.episode} — ${item.fileName}" else item.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        item.quality, item.sourceTag, item.codec, item.audio,
                                        if (item.sizeBytes > 0) Format.bytes(item.sizeBytes) else null,
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDownload(item) }) {
                                Icon(Icons.Filled.Download, contentDescription = "Download this file", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
