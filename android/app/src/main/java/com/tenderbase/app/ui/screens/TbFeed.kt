@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.tenderbase.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.DateFilter
import com.tenderbase.app.R
import com.tenderbase.app.RelativeTime
import com.tenderbase.app.SearchFilters
import com.tenderbase.app.SortOption
import com.tenderbase.app.StatusFilter
import com.tenderbase.app.Tender
import com.tenderbase.app.DocumentFilter
import com.tenderbase.app.ui.components.TbChipFlow
import com.tenderbase.app.ui.components.TbEmptyState
import com.tenderbase.app.ui.components.TbErrorState
import com.tenderbase.app.ui.components.TbFilterChip
import com.tenderbase.app.ui.components.TbOfflineBanner
import com.tenderbase.app.ui.components.TbRemovableChip
import com.tenderbase.app.ui.components.TbSearchField
import com.tenderbase.app.ui.components.TbSectionHeader
import com.tenderbase.app.ui.components.TbSkeletonCard
import com.tenderbase.app.ui.components.TenderCard
import com.tenderbase.app.ui.components.TbFilterSheet
import com.tenderbase.app.ui.theme.TbDimens
import com.tenderbase.app.ui.vm.DiscoveryViewModel
import com.tenderbase.app.ui.vm.FeedState
import com.tenderbase.app.ui.vm.QuickChip
import com.tenderbase.app.ui.vm.SaveSearchOutcome
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shared discovery feed used by both Home and Search tabs (one state machine,
 * zero duplicate fetch logic). Everything the spec §5 demands sits here in a
 * single lazily-scrolled column: quick chips → summary → controls → cards.
 */
@Composable
fun DiscoveryFeed(
    vm: DiscoveryViewModel,
    openDetail: (Int) -> Unit,
    openShare: (Tender) -> Unit,
    openSavedTab: () -> Unit,
    openDeadlines: () -> Unit,
    snack: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSearchField: Boolean = false,
    onSearchTap: (() -> Unit)? = null,
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val savedIds by vm.savedIds.collectAsStateWithLifecycle()
    val facets by vm.facets.collectAsStateWithLifecycle()
    val lastUpdated by vm.lastUpdated.collectAsStateWithLifecycle()
    val urgentSaved by vm.urgentSaved.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    var showSheet by rememberSaveable { mutableStateOf(false) }
    var sortMenu by rememberSaveable { mutableStateOf(false) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }

    val snSaved = stringResource(R.string.save_search_done)
    val snDup = stringResource(R.string.save_search_duplicate)
    val snFail = stringResource(R.string.save_search_failed)
    val snQueued = stringResource(R.string.saved_search_queued)
    val snSavedTender = stringResource(R.string.tender_saved)
    val snUnsavedTender = stringResource(R.string.tender_unsaved)

    val listState = rememberLazyListState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // Load more when scrolled near the end.
    LaunchedEffect(listState, feed.hasMore, feed.loading, feed.loadingMore) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (feed.hasMore && !feed.loadingMore && !feed.loading &&
                    lastVisible >= info.totalItemsCount - 4
                ) {
                    vm.loadMore()
                }
            }
    }

    val ptrState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refresh() },
        state = ptrState,
        modifier = modifier.fillMaxWidth(),
    ) {
        val activeChips = activeFilterChips(filters, vm)
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {

            if (showSearchField && onSearchTap != null) {
                item(key = "search-entry") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TbDimens.screenHMargin)
                            .padding(top = TbDimens.spaceSm)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSearchTap() },
                    ) {
                        TbSearchField(
                            query = filters.query,
                            onQueryChange = {},
                            active = false,
                        )
                    }
                }
            }

            item(key = "summary") {
                FeedSummaryRow(
                    vm = vm,
                    filters = filters,
                    total = feed.total,
                    loaded = feed.rows.size,
                    offline = feed.offline,
                    lastUpdated = lastUpdated,
                    onOpenSheet = { showSheet = true },
                    onOpenSort = { sortMenu = true },
                    onSaveSearch = { saveDialog = true },
                )
            }

            item(key = "urgent-banner") {
                if (urgentSaved.isNotEmpty()) {
                    UrgentSavedBanner(count = urgentSaved.size, onReview = openDeadlines)
                }
            }

            item(key = "quick-chips") {
                TbChipFlow(modifier = Modifier.padding(horizontal = TbDimens.screenHMargin)) {
                    quickChips(vm, openSavedTab).forEach { chip ->
                        TbFilterChip(
                            label = chip.label,
                            selected = chip.active,
                            onClick = chip.onClick,
                        )
                    }
                }
            }

            if (activeChips.isNotEmpty()) {
                item(key = "active-chips") {
                    TbChipFlow(modifier = Modifier.padding(horizontal = TbDimens.screenHMargin)) {
                        activeChips.forEach { (label, remove) ->
                            TbRemovableChip(label = label, onRemove = remove)
                        }
                    }
                }
            }

            when {
                feed.state == FeedState.LOADING && feed.rows.isEmpty() -> {
                    items(3) { TbSkeletonCard() }
                }

                feed.state == FeedState.ERROR -> {
                    item(key = "error") {
                        TbErrorState(
                            kind = feed.errorKind ?: com.tenderbase.app.UserErrorKind.GENERIC,
                            onRetry = { vm.retry() },
                        )
                    }
                }

                feed.state == FeedState.EMPTY -> {
                    item(key = "empty") {
                        val filtered = filters.hasActiveFilters() || filters.query.isNotBlank()
                        TbEmptyState(
                            icon = Icons.Filled.DateRange,
                            title = if (filtered) stringResource(R.string.no_results_title)
                            else stringResource(R.string.no_tenders_title),
                            body = if (filtered) stringResource(R.string.no_match_body)
                            else stringResource(R.string.no_tenders_body),
                            primaryLabel = if (filters.hasActiveFilters())
                                stringResource(R.string.clear_all) else null,
                            onPrimaryClick = if (filters.hasActiveFilters())
                                { { vm.clearAllFacetFilters() } } else null,
                            secondaryLabel = if (filters.query.isNotBlank())
                                stringResource(R.string.reset_search) else null,
                            onSecondaryClick = if (filters.query.isNotBlank())
                                { { vm.clearQuery() } } else null,
                        )
                    }
                }

                else -> {
                    if (feed.offline) {
                        item(key = "offline") { TbOfflineBanner() }
                    }
                    items(feed.rows, key = { it.id }) { tender ->
                        TenderCard(
                            tender = tender,
                            isSaved = tender.id in savedIds,
                            onClick = { openDetail(tender.id) },
                            onLongClick = { openShare(tender) },
                            onSaveToggle = {
                                vm.toggleSave(tender) { nowSaved ->
                                    snack(if (nowSaved) snSavedTender else snUnsavedTender)
                                }
                            },
                        )
                    }
                    item(key = "footer") {
                        FeedFooter(
                            hasMore = feed.hasMore,
                            loading = feed.loadingMore,
                            failed = feed.moreFailed,
                            onLoadMore = { vm.loadMore() },
                            visible = feed.rows.isNotEmpty(),
                        )
                    }
                }
            }

            item(key = "bottom-spacer") {
                Spacer(Modifier.height(TbDimens.spaceLg))
            }
        }
    }

    if (showSheet) {
        TbFilterSheet(
            current = filters,
            facets = facets,
            onDismiss = { showSheet = false },
            onApply = { applied ->
                showSheet = false
                vm.applySheetFilters(applied)
            },
        )
    }

    if (sortMenu) {
        val searching = filters.query.isNotBlank()
        val options = buildList {
            add(SortOption.NEWEST to R.string.sort_newest)
            add(SortOption.CLOSING to R.string.sort_closing)
            add(SortOption.UPDATED to R.string.sort_updated)
            if (searching) add(SortOption.RELEVANCE to R.string.sort_relevance)
        }
        // Render as a dialog: anchored menus inside pull-to-refresh columns
        // clip awkwardly on small screens.
        AlertDialog(
            onDismissRequest = { sortMenu = false },
            title = { Text(stringResource(R.string.sort_title)) },
            text = {
                Column {
                    options.forEach { (option, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    sortMenu = false
                                    vm.setSort(option)
                                }
                                .padding(12.dp),
                        ) {
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (filters.sort == option) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (filters.sort == option) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { sortMenu = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (saveDialog) {
        SaveSearchDialog(
            filters = filters,
            onDismiss = { saveDialog = false },
            onConfirm = { name ->
                saveDialog = false
                focusManager.clearFocus()
                vm.saveCurrentSearchNamed(name) { outcome ->
                    snack(
                        when (outcome) {
                            SaveSearchOutcome.SAVED -> snSaved
                            SaveSearchOutcome.DUPLICATE -> snDup
                            SaveSearchOutcome.FAILED -> snFail
                            SaveSearchOutcome.QUEUED_OFFLINE -> snQueued
                        }
                    )
                }
            },
        )
    }
}

// ------------------------------------------------------------------- header

@Composable
private fun FeedSummaryRow(
    vm: DiscoveryViewModel,
    filters: SearchFilters,
    total: Int,
    loaded: Int,
    offline: Boolean,
    lastUpdated: Long,
    onOpenSheet: () -> Unit,
    onOpenSort: () -> Unit,
    onSaveSearch: () -> Unit,
) {
    val countText = when {
        offline -> stringResource(R.plurals.opportunities_count, loaded)
        loaded in 1 until total ->
            stringResource(R.string.showing_range, 1, loaded, total)
        else -> stringResource(R.plurals.opportunities_count, total)
    }
    val rel = RelativeTime.label(System.currentTimeMillis(), lastUpdated)
    Column(Modifier.padding(start = TbDimens.screenHMargin, end = TbDimens.screenHMargin, top = TbDimens.spaceXl)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                countText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rel.isNotBlank()) {
                Spacer(Modifier.width(TbDimens.spaceSm))
                Text(
                    stringResource(R.string.feed_updated, rel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = TbDimens.spaceSm),
        ) {
            val n = filters.activeFilterCount()
            Surface(
                onClick = onOpenSheet,
                shape = RoundedCornerShape(10.dp),
                color = if (n > 0) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (n > 0) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(TbDimens.spaceXs))
                    Text(
                        if (n > 0) "${stringResource(R.string.filter_button)} ($n)"
                        else stringResource(R.string.filter_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (n > 0) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(TbDimens.spaceSm))
            Surface(
                onClick = onOpenSort,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Icon(
                        Icons.Filled.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(TbDimens.spaceXs))
                    Text(
                        "Sort: ${sortShortLabel(filters.sort)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSaveSearch) {
                Icon(
                    Icons.Filled.BookmarkAdded,
                    contentDescription = stringResource(R.string.cd_save_search),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun sortShortLabel(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.NEWEST -> R.string.sort_short_newest
        SortOption.CLOSING -> R.string.sort_closing
        SortOption.UPDATED -> R.string.sort_short_updated
        SortOption.RELEVANCE -> R.string.sort_relevance
    }
)

@Composable
private fun UrgentSavedBanner(count: Int, onReview: () -> Unit) {
    Surface(
        onClick = onReview,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin, vertical = TbDimens.spaceSm),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(TbDimens.iconSm))
            Spacer(Modifier.width(TbDimens.spaceSm))
            Text(
                pluralStringResource(R.plurals.urgent_saved_banner, count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.urgent_saved_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ----------------------------------------------------------------- helpers

private data class QuickChipUi(val label: String, val active: Boolean, val onClick: () -> Unit)

@Composable
private fun quickChips(
    vm: DiscoveryViewModel,
    openSavedTab: () -> Unit,
): List<QuickChipUi> {
    val closers = stringResource(R.string.quick_closing_soon)
    val newToday = stringResource(R.string.quick_new_today)
    val myCats = stringResource(R.string.quick_my_categories)
    val myProvs = stringResource(R.string.quick_my_provinces)
    val saved = stringResource(R.string.quick_saved)
    val withDocs = stringResource(R.string.quick_with_docs)
    return listOf(
        QuickChipUi(closers, vm.isQuickActive(QuickChip.CLOSING_SOON)) {
            vm.toggleQuick(QuickChip.CLOSING_SOON)
        },
        QuickChipUi(newToday, vm.isQuickActive(QuickChip.NEW_TODAY)) {
            vm.toggleQuick(QuickChip.NEW_TODAY)
        },
        QuickChipUi(myCats, vm.isQuickActive(QuickChip.MY_CATEGORIES)) {
            vm.toggleQuick(QuickChip.MY_CATEGORIES)
        },
        QuickChipUi(myProvs, vm.isQuickActive(QuickChip.MY_PROVINCES)) {
            vm.toggleQuick(QuickChip.MY_PROVINCES)
        },
        QuickChipUi(saved, false, openSavedTab),
        QuickChipUi(withDocs, vm.isQuickActive(QuickChip.WITH_DOCS)) {
            vm.toggleQuick(QuickChip.WITH_DOCS)
        },
    )
}

@Composable
private fun activeFilterChips(filters: SearchFilters, vm: DiscoveryViewModel): List<Pair<String, () -> Unit>> {
    val out = mutableListOf<Pair<String, () -> Unit>>()
    filters.provinces.forEach { out += it to { vm.removeProvince(it) } }
    filters.categories.forEach { out += it to { vm.removeCategory(it) } }
    filters.sources.forEach { out += it to { vm.removeSource(it) } }
    filters.status?.let { st ->
        val label = stringResource(
            when (st) {
                StatusFilter.OPEN -> R.string.status_open
                StatusFilter.CLOSING_SOON -> R.string.status_closing_soon
                StatusFilter.CLOSED -> R.string.status_closed
            }
        )
        out += label to { vm.clearStatus() }
    }
    if (filters.dateFilter != DateFilter.ANY) {
        out += dateFilterLabel(filters) to { vm.clearDate() }
    }
    filters.organisation?.takeIf { it.isNotBlank() }?.let { org ->
        out += org to { vm.clearOrg() }
    }
    if (filters.docs != DocumentFilter.ANY) {
        val docsLabel = stringResource(
            when (filters.docs) {
                DocumentFilter.HAS_DOCS -> R.string.docs_has_documents
                DocumentFilter.HAS_NOTICE -> R.string.docs_has_notice
                DocumentFilter.HAS_SPEC -> R.string.docs_has_spec
                DocumentFilter.ANY -> R.string.docs_any
            }
        )
        out += docsLabel to { vm.clearDocs() }
    }
    return out
}

@Composable
private fun dateFilterLabel(f: SearchFilters): String = when (f.dateFilter) {
    DateFilter.PUBLISHED_TODAY -> stringResource(R.string.published_today)
    DateFilter.PUBLISHED_7D -> stringResource(R.string.published_7d)
    DateFilter.PUBLISHED_30D -> stringResource(R.string.published_30d)
    DateFilter.CLOSING_7D -> stringResource(R.string.closing_7d)
    DateFilter.CLOSING_14D -> stringResource(R.string.closing_14d)
    DateFilter.CLOSING_30D -> stringResource(R.string.closing_30d)
    DateFilter.CLOSING_CUSTOM -> listOfNotNull(f.closingAfter, f.closingBefore).joinToString(" – ")
        .ifEmpty { stringResource(R.string.closing_custom) }
    DateFilter.ANY -> ""
}

@Composable
private fun FeedFooter(
    hasMore: Boolean,
    loading: Boolean,
    failed: Boolean,
    onLoadMore: () -> Unit,
    visible: Boolean,
) {
    if (!visible) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TbDimens.spaceLg),
    ) {
        when {
            loading -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(120.dp)
                        .height(3.dp),
                )
                Spacer(Modifier.height(TbDimens.spaceSm))
                Text(
                    stringResource(R.string.loading_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            failed -> {
                TextButton(onClick = onLoadMore) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(TbDimens.spaceXs))
                    Text(stringResource(R.string.retry_load_more))
                }
            }
            !hasMore -> {
                Text(
                    stringResource(R.string.end_of_results),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SaveSearchDialog(
    filters: SearchFilters,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable {
        mutableStateOf(filters.query.ifBlank { filters.summary() }.take(40))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_search_title)) },
        text = {
            Column {
                Text(
                    filters.summary().ifBlank { stringResource(R.string.search_hint) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(TbDimens.spaceMd))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.save_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save_search_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
