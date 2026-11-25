package com.lanfetch.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.Facets
import com.lanfetch.app.filter.SortBy
import com.lanfetch.app.ui.components.LanesDivider
import com.lanfetch.app.ui.components.LanesSectionLabel
import com.lanfetch.app.ui.theme.LanesPalette
import com.lanfetch.app.ui.theme.LanesRadii
import com.lanfetch.app.ui.theme.LanesSpacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryFilterSheet(
    filter: CatalogFilter,
    facets: Facets,
    onChange: (CatalogFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LanesPalette.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LanesSpacing.ScreenHorizontal)
                .padding(bottom = LanesSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(LanesSpacing.Md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filter & Sort",
                    style = MaterialTheme.typography.titleLarge,
                    color = LanesPalette.InkPrimary,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onClear,
                    shape = RoundedCornerShape(LanesRadii.Control),
                ) {
                    Text("Reset")
                }
            }

            LanesDivider()

            LanesSectionLabel("Sort order")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
            ) {
                SortBy.entries.forEach { sort ->
                    StyledFilterChip(
                        selected = filter.sort == sort,
                        label = sortLabel(sort),
                        onClick = { onChange(filter.copy(sort = sort)) },
                    )
                }
            }

            if (facets.qualities.isNotEmpty()) {
                FacetSection("Resolution", facets.qualities, filter.qualities) {
                    onChange(filter.copy(qualities = filter.qualities.toggleValue(it)))
                }
            }
            if (facets.categories.size > 1) {
                FacetSection("Categories", facets.categories, filter.categories) {
                    onChange(filter.copy(categories = filter.categories.toggleValue(it)))
                }
            }
            if (facets.sourceNames.size > 1) {
                FacetSection("Servers", facets.sourceNames, filter.sourceNames) {
                    onChange(filter.copy(sourceNames = filter.sourceNames.toggleValue(it)))
                }
            }
            if (facets.languages.isNotEmpty()) {
                FacetSection("Language", facets.languages, filter.languages) {
                    onChange(filter.copy(languages = filter.languages.toggleValue(it)))
                }
            }
            if (facets.sourceTags.isNotEmpty()) {
                FacetSection("Release format", facets.sourceTags, filter.sourceTags) {
                    onChange(filter.copy(sourceTags = filter.sourceTags.toggleValue(it)))
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
                LanesSectionLabel("Release year (${range.start.toInt()} – ${range.endInclusive.toInt()})")
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

            LanesSectionLabel("Minimum size")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
            ) {
                val sizes = listOf(
                    null to "Any",
                    700_000_000L to "700 MB+",
                    1_500_000_000L to "1.5 GB+",
                    4_000_000_000L to "4 GB+",
                )
                sizes.forEach { (bytes, label) ->
                    StyledFilterChip(
                        selected = filter.minSizeBytes == bytes,
                        label = label,
                        onClick = { onChange(filter.copy(minSizeBytes = bytes)) },
                    )
                }
            }

            LanesDivider()

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Favorites only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LanesPalette.InkPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = filter.favoritesOnly,
                    onCheckedChange = { onChange(filter.copy(favoritesOnly = it)) },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dual / multi audio only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LanesPalette.InkPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = filter.dualAudioOnly,
                    onCheckedChange = { onChange(filter.copy(dualAudioOnly = it)) },
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(LanesRadii.Control),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LanesPalette.Accent,
                    contentColor = LanesPalette.OnAccent,
                ),
            ) {
                Text("Done")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetSection(
    title: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LanesSpacing.Sm)) {
        LanesSectionLabel(title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
        ) {
            options.forEach { value ->
                StyledFilterChip(
                    selected = value in selected,
                    label = value,
                    onClick = { onToggle(value) },
                )
            }
        }
    }
}

@Composable
private fun StyledFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(LanesRadii.Control),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = LanesPalette.AccentContainer,
            selectedLabelColor = LanesPalette.Accent,
            containerColor = LanesPalette.SurfaceElevated,
            labelColor = LanesPalette.InkSecondary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = LanesPalette.Rule,
            selectedBorderColor = LanesPalette.Accent,
        ),
    )
}

fun sortLabel(sort: SortBy): String = when (sort) {
    SortBy.NEWEST -> "Feed"
    SortBy.TITLE -> "A–Z"
    SortBy.YEAR_DESC -> "Year"
    SortBy.SIZE_DESC -> "Largest"
    SortBy.SIZE_ASC -> "Smallest"
}

private fun Set<String>.toggleValue(value: String): Set<String> =
    if (value in this) this - value else this + value
