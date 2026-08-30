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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckDone
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.DateUtils
import com.tenderbase.app.DeadlineStatus
import com.tenderbase.app.DeadlineTier
import com.tenderbase.app.R
import com.tenderbase.app.Tender
import com.tenderbase.app.TenderTaxonomy
import com.tenderbase.app.ui.components.TbChipFlow
import com.tenderbase.app.ui.components.TbEmptyState
import com.tenderbase.app.ui.components.TbFilterChip
import com.tenderbase.app.ui.components.TbSearchField
import com.tenderbase.app.ui.components.TbSectionHeader
import com.tenderbase.app.ui.components.TbSettingsIconAction
import com.tenderbase.app.ui.components.TbTopBar
import com.tenderbase.app.ui.components.SavedTenderCard
import com.tenderbase.app.ui.components.TbBrandMark
import com.tenderbase.app.ui.components.TbNotificationRow
import com.tenderbase.app.ui.TbNav
import com.tenderbase.app.ui.TbTab
import com.tenderbase.app.ui.theme.TbDimens
import com.tenderbase.app.ui.vm.DiscoveryViewModel
import com.tenderbase.app.ui.vm.NotificationsUiRow
import com.tenderbase.app.ui.vm.NotificationsViewModel
import com.tenderbase.app.ui.vm.SavedRow
import com.tenderbase.app.ui.vm.SavedViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ------------------------------------------------------------------- HOME

@Composable
fun HomeScreen(
    vm: DiscoveryViewModel,
    nav: TbNav,
    snack: (String) -> Unit,
    openShare: (Tender) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TbTopBar(
            title = stringResource(R.string.app_name),
            actions = {
                val unread by vm.unreadCount.collectAsStateWithLifecycle()
                Box {
                    IconButton(onClick = { nav.switchTab(TbTab.ALERTS) }) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.notifications_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (unread > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 10.dp, end = 10.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }
                TbSettingsIconAction(onClick = { nav.openSettings() })
            },
        )
        Column(Modifier.padding(horizontal = TbDimens.screenHMargin)) {
            Text(
                stringResource(R.string.find_opportunities),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(TbDimens.spaceMd))
            Surface(
                onClick = { nav.switchTab(TbTab.SEARCH) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                TbSearchField(
                    query = vm.filters.value.query,
                    onQueryChange = {},
                    active = false,
                )
            }
            Spacer(Modifier.height(TbDimens.spaceSm))
        }
        DiscoveryFeed(
            vm = vm,
            openDetail = nav.openDetail,
            openShare = openShare,
            openSavedTab = { nav.switchTab(TbTab.SAVED) },
            openDeadlines = nav.openDeadlines,
            snack = snack,
            modifier = Modifier.weight(1f),
        )
    }
}


// ----------------------------------------------------------------- SEARCH

@Composable
fun SearchScreen(
    vm: DiscoveryViewModel,
    nav: TbNav,
    snack: (String) -> Unit,
    openShare: (Tender) -> Unit,
) {
    val query = vm.filters.value.query
    val filters = vm.filters.value
    val recents by vm.recentSearches.collectAsStateWithLifecycle()
    val facets by vm.facets.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TbDimens.spaceSm, vertical = TbDimens.spaceSm),
        ) {
            IconButton(onClick = {
                keyboard?.hide()
                focusManager.clearFocus()
                nav.switchTab(TbTab.HOME)
            }) {
                Icon(
                    if (query.isEmpty()) Icons.AutoMirrored.Filled.ArrowBack
                    else Icons.Filled.Search,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Box(Modifier.weight(1f)) {
                TbSearchField(
                    query = query,
                    onQueryChange = { vm.onType(it) },
                    active = true,
                    placeholder = stringResource(R.string.search_hint_full),
                    onImeSearch = {
                        vm.commitQuery()
                        keyboard?.hide()
                        focusManager.clearFocus()
                    },
                    focusRequester = focusRequester,
                )
            }
        }

        if (query.isBlank() && !filters.hasActiveFilters()) {
            SearchSuggestions(
                recents = recents,
                popularCategories = facets?.categories?.take(8)?.map { it.name }
                    ?: TenderTaxonomy.CATEGORIES.take(8),
                suggested = TenderTaxonomy.SUGGESTED_SEARCHES,
                onPick = { text -> vm.searchFor(text) },
                onRemoveRecent = { vm.removeRecentSearch(it) },
                onClearRecents = { vm.clearRecentSearches() },
                modifier = Modifier.weight(1f),
            )
        } else {
            DiscoveryFeed(
                vm = vm,
                openDetail = nav.openDetail,
                openShare = openShare,
                openSavedTab = { nav.switchTab(TbTab.SAVED) },
                openDeadlines = nav.openDeadlines,
                snack = snack,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchSuggestions(
    recents: List<String>,
    popularCategories: List<String>,
    suggested: List<String>,
    onPick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (recents.isNotEmpty()) {
            item {
                TbSectionHeader(
                    label = stringResource(R.string.recent_searches),
                    trailing = {
                        TextButton(onClick = onClearRecents) {
                            Text(
                                stringResource(R.string.clear_recents),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                )
            }
            items(recents) { term ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onPick(term) }
                        .padding(horizontal = TbDimens.screenHMargin, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(TbDimens.iconMd),
                    )
                    Spacer(Modifier.width(TbDimens.spaceMd))
                    Text(
                        term,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemoveRecent(term) }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.cd_remove_recent, term),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(TbDimens.iconSm),
                        )
                    }
                }
            }
        }

        item {
            TbSectionHeader(label = stringResource(R.string.suggested_searches))
        }
        items(suggested) { term ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onPick(term) }
                    .padding(horizontal = TbDimens.screenHMargin, vertical = 10.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(TbDimens.iconSm),
                )
                Spacer(Modifier.width(TbDimens.spaceMd))
                Text(
                    term,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        item {
            TbSectionHeader(label = stringResource(R.string.popular_categories))
        }
        item {
            TbChipFlow(modifier = Modifier.padding(horizontal = TbDimens.screenHMargin)) {
                popularCategories.forEach { cat ->
                    TbFilterChip(
                        label = TenderTaxonomy.displayName(cat),
                        selected = false,
                        onClick = { onPick(TenderTaxonomy.displayName(cat) + " tenders") },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
    }
}

// ------------------------------------------------------------------ SAVED

@Composable
fun SavedScreen(
    vm: SavedViewModel,
    nav: TbNav,
    openShare: (Tender) -> Unit,
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TbTopBar(
            title = stringResource(R.string.saved_title),
            subtitle = stringResource(R.string.no_opportunities_count, rows.size),
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TbDimens.screenHMargin, vertical = TbDimens.spaceSm),
        ) {
            val labels = listOf(
                stringResource(R.string.saved_tab_all),
                stringResource(R.string.saved_tab_closing),
                stringResource(R.string.saved_tab_recent),
            )
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        val shown = when (tabIndex) {
            1 -> rows.filter { row ->
                val tier = DeadlineStatus.tierOf(row.tender)
                tier == DeadlineTier.URGENT || tier == DeadlineTier.CLOSING_SOON ||
                    tier == DeadlineTier.UPCOMING
            }.sortedBy { DateUtils.toMillis(it.tender.closingAt, it.tender.closingDate) ?: Long.MAX_VALUE }
            2 -> rows.sortedByDescending { it.savedAt }
            else -> rows.sortedBy {
                DateUtils.toMillis(it.tender.closingAt, it.tender.closingDate) ?: Long.MAX_VALUE
            }
        }

        if (shown.isEmpty() && rows.isEmpty()) {
            TbEmptyState(
                icon = Icons.Filled.CheckDone,
                title = stringResource(R.string.empty_saved_title),
                body = stringResource(R.string.empty_saved_body),
                primaryLabel = stringResource(R.string.find_tenders),
                onPrimaryClick = { nav.switchTab(TbTab.SEARCH) },
            )
        } else if (shown.isEmpty()) {
            Text(
                stringResource(R.string.saved_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = TbDimens.screenHMargin,
                    vertical = TbDimens.spaceXxl,
                ),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.tender.id }) { row ->
                    SavedTenderCard(
                        tender = row.tender,
                        done = row.done,
                        total = row.total,
                        hasNote = row.hasNote,
                        onClick = { nav.openDetail(row.tender.id) },
                        onLongClick = { openShare(row.tender) },
                        onRemove = { vm.unsave(row.tender.id) },
                    )
                }
                item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
            }
        }
    }
}

// ----------------------------------------------------------- NOTIFICATIONS

@Composable
fun NotificationsScreen(vm: NotificationsViewModel, nav: TbNav) {
    val items by vm.items.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TbTopBar(
            title = stringResource(R.string.notifications_title),
            actions = {
                if (items.any { !it.isRead }) {
                    TextButton(onClick = { vm.markAllRead() }) {
                        Text(
                            stringResource(R.string.mark_all_read),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            },
        )
        if (items.isEmpty()) {
            TbEmptyState(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.empty_notif_title),
                body = stringResource(R.string.empty_notif_body),
            )
        } else {
            val groups = com.tenderbase.app.NotificationGroups.groupBy(items, { it.timestamp })
            LazyColumn(Modifier.fillMaxSize()) {
                com.tenderbase.app.NotificationBucket.entries.forEach { bucket ->
                    val bucketItems = groups[bucket] ?: return@forEach
                    item(key = "hdr-$bucket") {
                        Text(
                            when (bucket) {
                                com.tenderbase.app.NotificationBucket.TODAY ->
                                    stringResource(R.string.group_today)
                                com.tenderbase.app.NotificationBucket.YESTERDAY ->
                                    stringResource(R.string.group_yesterday)
                                else -> stringResource(R.string.group_earlier)
                            }.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = TbDimens.screenHMargin,
                                end = TbDimens.screenHMargin,
                                top = TbDimens.spaceLg,
                                bottom = TbDimens.spaceSm,
                            ),
                        )
                    }
                    items(bucketItems, key = { it.id }) { n ->
                        TbNotificationRow(
                            title = n.title,
                            body = n.body,
                            timestamp = n.timestamp,
                            isRead = n.isRead,
                            onClick = {
                                vm.markRead(n.id)
                                if (n.tenderId > 0) nav.openDetail(n.tenderId)
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
            }
        }
    }
}

// --------------------------------------------------------------------- MORE

@Composable
fun MoreScreen(nav: TbNav) {
    var showAbout by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TbTopBar(title = stringResource(R.string.more_title))
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TbDimens.screenHMargin, vertical = TbDimens.spaceLg),
                ) {
                    TbBrandMark(size = 46.dp)
                    Spacer(Modifier.width(TbDimens.spaceMd))
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(R.string.more_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { TbSectionHeader(label = stringResource(R.string.deadlines_title)) }
            item {
                TbSettingsGroup {
                    TbSettingsRow(
                        icon = Icons.Filled.DateRange,
                        title = stringResource(R.string.more_deadlines),
                        subtitle = stringResource(R.string.deadlines_subtitle_short),
                        onClick = nav.openDeadlines,
                    )
                    TbSettingsRow(
                        icon = Icons.Filled.Download,
                        title = stringResource(R.string.more_downloads),
                        onClick = nav.openDownloads,
                    )
                    TbSettingsRow(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.more_saved_searches),
                        subtitle = stringResource(R.string.saved_searches_subtitle),
                        onClick = nav.openSavedSearches,
                    )
                }
            }
            item { TbSectionHeader(label = stringResource(R.string.settings_alerts_prefs)) }
            item {
                TbSettingsGroup {
                    TbSettingsRow(
                        icon = Icons.Filled.Category,
                        title = stringResource(R.string.more_categories),
                        onClick = { nav.openPreferences("categories") },
                    )
                    TbSettingsRow(
                        icon = Icons.Filled.Map,
                        title = stringResource(R.string.more_provinces),
                        onClick = { nav.openPreferences("provinces") },
                    )
                }
            }
            item { TbSectionHeader(label = stringResource(R.string.settings_title)) }
            item {
                TbSettingsGroup {
                    TbSettingsRow(
                        icon = Icons.Filled.Settings,
                        title = stringResource(R.string.more_settings),
                        subtitle = stringResource(R.string.settings_row_sub),
                        onClick = nav.openSettings,
                    )
                    TbSettingsRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.more_about),
                        onClick = { showAbout = true },
                    )
                }
            }
            item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
        }
    }

    if (showAbout) {
        val notes = remember { com.tenderbase.app.Changelog.releases.first() }
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.more_about)) },
            text = {
                Column {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val version = remember {
                        runCatching {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.2"
                        }.getOrDefault("1.2")
                    }
                    Text(
                        stringResource(R.string.version_format, version),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(TbDimens.spaceSm))
                    Text(
                        stringResource(R.string.about_tenderbase_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(TbDimens.spaceMd))
                    Text(
                        "What's new",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(TbDimens.spaceXs))
                    notes.highlights.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.about_close))
                }
            },
        )
    }
}

// ------------------------------------------------------- shared row widgets

/** Grouped settings surface: rows packed in one rounded container. */
@Composable
fun TbSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin, vertical = TbDimens.spaceSm),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Column(content = content)
    }
}

@Composable
fun TbSettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = TbDimens.spaceLg, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(TbDimens.iconSm),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(TbDimens.spaceMd))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing() else {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(TbDimens.iconSm),
            )
        }
    }
}
