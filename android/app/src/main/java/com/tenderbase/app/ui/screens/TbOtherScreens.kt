@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.tenderbase.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.ApiClient
import com.tenderbase.app.Dashboard
import com.tenderbase.app.DateUtils
import com.tenderbase.app.R
import com.tenderbase.app.RelativeTime
import com.tenderbase.app.SearchFilters
import com.tenderbase.app.Tender
import com.tenderbase.app.TenderActions
import com.tenderbase.app.TenderRepository
import com.tenderbase.app.TenderTaxonomy
import com.tenderbase.app.ui.components.TbChipFlow
import com.tenderbase.app.ui.components.TbEmptyState
import com.tenderbase.app.ui.components.TbErrorState
import com.tenderbase.app.ui.components.TbFilterChip
import com.tenderbase.app.ui.components.TbNotificationRow
import com.tenderbase.app.ui.components.TbPrimaryButton
import com.tenderbase.app.ui.components.TbSectionHeader
import com.tenderbase.app.ui.components.TbSettingsGroup
import com.tenderbase.app.ui.components.TbSettingsRow
import com.tenderbase.app.ui.components.TbSkeletonCard
import com.tenderbase.app.ui.components.TbTopBar
import com.tenderbase.app.ui.components.TenderCard
import com.tenderbase.app.ui.theme.TbDimens
import com.tenderbase.app.ui.vm.DeadlinesViewModel
import com.tenderbase.app.ui.vm.DiscoveryViewModel
import com.tenderbase.app.ui.vm.DownloadRow
import com.tenderbase.app.ui.vm.DownloadsViewModel
import com.tenderbase.app.ui.vm.SavedSearchesViewModel
import com.tenderbase.app.ui.vm.SettingsViewModel
import com.tenderbase.app.ui.vm.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================================================================ DEADLINES

@Composable
fun DeadlinesScreen(
    onBack: () -> Unit,
    openDetail: (Int) -> Unit,
    openAlerts: () -> Unit,
) {
    val vm: DeadlinesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val (closingSoon, loading, errorKind) by vm.closingSoon.collectAsStateWithLifecycle()
    val showSavedIds by vm.savedIds.collectAsStateWithLifecycle()
    val saved by vm.savedTenders.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    var refreshing by rememberSaveable { mutableStateOf(false) }
    val ptr = rememberPullToRefreshState()

    ScaffoldishColumn(
        title = stringResource(R.string.deadlines_title),
        subtitle = if (errorKind != null) stringResource(R.string.deadlines_partial_error)
        else stringResource(
            R.string.deadlines_subtitle,
            closingSoon.size,
            saved.count {
                DateUtils.urgency(it.closingAt, it.closingDate, it.deadlineState) !=
                    DateUtils.Urgency.CLOSED
            },
        ),
        onBack = onBack,
    ) {
        LaunchedEffect(loading) {
            if (!loading && refreshing) {
                delay(350)
                refreshing = false
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                vm.load()
            },
            state = ptr,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val hasAnything =
                closingSoon.isNotEmpty() || saved.isNotEmpty() || alerts.isNotEmpty()
            if (!hasAnything && errorKind != null) {
                TbErrorState(kind = errorKind, onRetry = { vm.load() })
            } else if (!hasAnything && loading) {
                Column {
                    repeat(3) { TbSkeletonCard(lines = 2) }
                }
            } else if (!hasAnything) {
                TbEmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = stringResource(R.string.deadlines_empty),
                    body = stringResource(R.string.deadlines_empty_body),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (closingSoon.isNotEmpty()) {
                        item(key = "cs-hdr") {
                            TbSectionHeader(
                                label = stringResource(R.string.section_closing_week, closingSoon.size)
                            )
                        }
                        items(closingSoon.take(10), key = { "cs-${it.id}" }) { t ->
                            TenderCard(
                                tender = t,
                                isSaved = t.id in showSavedIds,
                                onClick = { openDetail(t.id) },
                                onSaveToggle = { vm.toggleSave(t) },
                                compact = true,
                            )
                        }
                    }
                    for ((bucket, items_) in Dashboard.groupByDeadline(saved)) {
                        if (items_.isEmpty()) continue
                        item(key = "bkt-$bucket") {
                            TbSectionHeader(label = bucketLabel(bucket, items_.size))
                        }
                        items(items_, key = { "sv-${bucket}-${it.id}" }) { t ->
                            TenderCard(
                                tender = t,
                                isSaved = true,
                                onClick = { openDetail(t.id) },
                                onSaveToggle = { vm.toggleSave(t) },
                                compact = true,
                            )
                        }
                    }
                    if (alerts.isNotEmpty()) {
                        item(key = "al-hdr") {
                            TbSectionHeader(
                                label = stringResource(R.string.section_alerts, alerts.size),
                                trailing = {
                                    TextButton(onClick = openAlerts) {
                                        Text(stringResource(R.string.view_all_alerts))
                                    }
                                },
                            )
                        }
                        items(alerts.take(3), key = { "al-${it.id}" }) { n ->
                            TbNotificationRow(
                                title = n.title,
                                body = n.body,
                                timestamp = n.timestamp,
                                isRead = n.isRead,
                                onClick = { openDetail(n.tenderId) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
                }
            }
        }
    }
}

@Composable
private fun bucketLabel(bucket: Dashboard.Bucket, count: Int): String = stringResource(
    when (bucket) {
        Dashboard.Bucket.CLOSED -> R.string.bucket_closed
        Dashboard.Bucket.TODAY -> R.string.bucket_today
        Dashboard.Bucket.THIS_WEEK -> R.string.bucket_this_week
        Dashboard.Bucket.TWO_WEEKS -> R.string.bucket_two_weeks
        Dashboard.Bucket.LATER -> R.string.bucket_later
        Dashboard.Bucket.NO_DATE -> R.string.bucket_no_date
    },
    count,
)

// ================================================================ DOWNLOADS

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    openTender: (Int) -> Unit,
    snack: (String) -> Unit,
) {
    val vm: DownloadsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val files by vm.files.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<java.io.File?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { vm.refresh() }

    ScaffoldishColumn(
        title = stringResource(R.string.downloads_title),
        subtitle = stringResource(R.string.downloads_count, files.size),
        onBack = onBack,
    ) {
        if (files.isEmpty()) {
            TbEmptyState(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.downloads_empty_title),
                body = stringResource(R.string.downloads_empty_body),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.file.absolutePath }) { row ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = TbDimens.screenHMargin,
                                vertical = 4.dp,
                            ),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(TbDimens.iconSm),
                                )
                            }
                            Spacer(Modifier.width(TbDimens.spaceMd))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    com.tenderbase.app.DocumentStore.humanName(row.file),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.sizeLabel + " · " +
                                        SimpleDateFormat(
                                            "d MMM yyyy", Locale.getDefault()
                                        ).format(Date(row.file.lastModified())),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                openDocumentWith(context, row.file) {
                                    snack(context.getString(R.string.open_failed))
                                }
                            }) {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = stringResource(R.string.cd_open_file),
                                )
                            }
                            IconButton(onClick = {
                                shareDownloadedFile(context, row.file)
                            }) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.cd_share_file),
                                )
                            }
                            IconButton(onClick = { toDelete = row.file }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.cd_delete_file),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_file_title)) },
            text = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(f)
                    toDelete = null
                    snack(stringResource(R.string.delete_done))
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ============================================================ SAVED SEARCHES

@Composable
fun SavedSearchesScreen(
    onBack: () -> Unit,
    onApply: (SearchFilters) -> Unit,
    snack: (String) -> Unit,
) {
    val vm: SavedSearchesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(Unit) { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    val snDeleted = stringResource(R.string.saved_search_deleted)
    val snFailed = stringResource(R.string.saved_search_delete_failed)

    ScaffoldishColumn(
        title = stringResource(R.string.saved_searches_title),
        subtitle = if (state.loading || state.error != null) null
        else stringResource(R.plurals.saved_searches_count, state.items.size),
        onBack = onBack,
    ) {
        when {
            state.loading -> Column { repeat(2) { TbSkeletonCard(lines = 2) } }
            state.error != null && state.items.isEmpty() ->
                TbErrorState(kind = state.error!!, onRetry = { vm.load() })
            state.items.isEmpty() ->
                TbEmptyState(
                    icon = Icons.Filled.AlternateEmail,
                    title = stringResource(R.string.saved_searches_empty_title),
                    body = stringResource(R.string.saved_searches_empty_body),
                )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.id }) { info ->
                    Surface(
                        onClick = {
                            onApply(SearchFilters.fromSavedSearchPayload(info.payload))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = TbDimens.screenHMargin,
                                vertical = 4.dp,
                            ),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        info.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        SearchFilters.fromSavedSearchPayload(info.payload)
                                            .summary()
                                            .ifBlank { stringResource(R.string.date_any) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = {
                                    vm.delete(
                                        info,
                                        onDeleted = { snack(snDeleted) },
                                        onFail = { snack(snFailed) },
                                    )
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.cd_delete_search),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Notifications,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(TbDimens.spaceSm))
                                Text(
                                    stringResource(R.string.cd_search_alerts),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                Switch(
                                    checked = info.alertsEnabled,
                                    onCheckedChange = { on ->
                                        vm.setAlerts(info, on) { snack(snFailed) }
                                    },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
            }
        }
    }
}

// ============================================================== PREFERENCES

@Composable
fun PreferencesScreen(
    type: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember(context) { TenderRepository(context) }
    val isCategories = type != com.tenderbase.app.PreferencesActivity.TYPE_PROVINCES
    val options = if (isCategories) TenderTaxonomy.CATEGORIES else TenderTaxonomy.PROVINCES
    var selected by rememberSaveable {
        mutableStateOf(
            (if (isCategories) repo.getSelectedCategories() else repo.getSelectedProvinces())
                .sorted()
        )
    }

    ScaffoldishColumn(
        title = stringResource(
            if (isCategories) R.string.pref_categories_title else R.string.pref_provinces_title
        ),
        subtitle = stringResource(
            if (isCategories) R.string.pref_categories_desc else R.string.pref_provinces_desc
        ),
        onBack = onBack,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.padding(horizontal = TbDimens.screenHMargin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { selected = options }) {
                    Text(stringResource(R.string.pref_select_all))
                }
                TextButton(onClick = { selected = emptyList() }) {
                    Text(stringResource(R.string.pref_select_none))
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = TbDimens.screenHMargin - 8.dp,
                    vertical = TbDimens.spaceSm,
                ),
            ) {
                item {
                    TbChipFlow {
                        options.forEach { name ->
                            TbFilterChip(
                                label = TenderTaxonomy.displayName(name),
                                selected = selected.isEmpty() || name in selected,
                                onClick = {
                                    selected = if (name in selected) {
                                        selected - name
                                    } else if (selected.isEmpty()) {
                                        options.filter { it != name }
                                    } else {
                                        selected + name
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TbDimens.spaceLg),
        ) {
            TbPrimaryButton(
                text = stringResource(R.string.save),
                onClick = {
                    if (isCategories) repo.setSelectedCategories(selected.toSet())
                    else repo.setSelectedProvinces(selected.toSet())
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ================================================================= SETTINGS

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    openSavedSearches: () -> Unit,
    snack: (String) -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
) {
    val vm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember(context) { TenderRepository(context) }
    LaunchedEffect(Unit) { vm.checkConnection() }

    val connection by vm.connection.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val snHidden = stringResource(R.string.hidden_tenders_none)
    val snCleared = stringResource(R.string.downloads_cleared)
    val snRestoreFailed = stringResource(R.string.workspace_restore_failed)
    val snRestoreNone = stringResource(R.string.workspace_restore_none)
    val scope = rememberCoroutineScope()

    val notifOn = remember {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    ScaffoldishColumn(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { TbSectionHeader(label = stringResource(R.string.settings_notifications)) }
            item {
                TbSettingsGroup {
                    TbSettingsRow(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.notif_row_title),
                        subtitle = stringResource(
                            if (notifOn) R.string.notif_row_opened_granted
                            else R.string.notif_row_opened_denied
                        ),
                        onClick = { openNotificationSettings(context) },
                    )
                    TbSettingsRow(
                        icon = Icons.Filled.AlternateEmail,
                        title = stringResource(R.string.saved_searches_title),
                        subtitle = stringResource(R.string.saved_searches_subtitle),
                        onClick = openSavedSearches,
                    )
                }
            }

            item { TbSectionHeader(label = stringResource(R.string.settings_appearance)) }
            item {
                TbSettingsGroup {
                    TbSettingsRow(
                        icon = Icons.Filled.Palette,
                        title = stringResource(R.string.theme_label),
                        subtitle = stringResource(
                            when (theme) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            }
                        ),
                        onClick = { showThemePicker = true },
                    )
                }
            }

            item { TbSectionHeader(label = stringResource(R.string.settings_data)) }
            item {
                TbSettingsGroup {
                    val connLabel = when (connection) {
                        null -> stringResource(R.string.api_checking)
                        true -> stringResource(R.string.api_connected)
                        false -> stringResource(R.string.api_offline)
                    }
                    TbSettingsRow(
                        icon = Icons.Filled.Refresh,
                        title = stringResource(R.string.settings_connection_title),
                        subtitle = connLabel,
                        onClick = { vm.checkConnection() },
                    )
                    val last = repo.lastFeedUpdate()
                    TbSettingsRow(
                        icon = Icons.Filled.History,
                        title = stringResource(R.string.cache_row_title),
                        subtitle = if (last > 0) {
                            RelativeTime.label(System.currentTimeMillis(), last)
                                .let { stringResource(R.string.cache_row_updated, it) }
                        } else stringResource(R.string.cache_row_never),
                        onClick = { vm.checkConnection() },
                    )
                }
            }

            item { TbSectionHeader(label = stringResource(R.string.settings_privacy)) }
            item {
                val hiddenCount = repo.hiddenTenderIds().size
                TbSettingsGroup {
                    TbSettingsRow(
                        icon = Icons.Filled.Visibility,
                        title = stringResource(R.string.hidden_tenders_title),
                        subtitle = if (hiddenCount == 0) snHidden
                        else stringResource(R.string.hidden_tenders_count, hiddenCount),
                        onClick = {
                            if (hiddenCount > 0) {
                                repo.unhideAllTenders()
                                snack(snHidden)
                            }
                        },
                    )
                    TbSettingsRow(
                        icon = Icons.Filled.Restore,
                        title = stringResource(R.string.workspace_restore_title),
                        subtitle = stringResource(R.string.workspace_restore_body),
                        onClick = { confirmRestore = true },
                    )
                    TbSettingsRow(
                        icon = Icons.Filled.Delete,
                        title = stringResource(R.string.settings_clear_downloads),
                        subtitle = stringResource(R.string.settings_clear_downloads_desc),
                        onClick = { confirmClear = true },
                    )
                }
            }

            item {
                val version = remember(context) {
                    runCatching {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName ?: "—"
                    }.getOrDefault("—")
                }
                Column(Modifier.padding(TbDimens.screenHMargin)) {
                    Spacer(Modifier.height(TbDimens.spaceLg))
                    Text(
                        stringResource(R.string.version_format, version),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            item { Spacer(Modifier.height(TbDimens.spaceXxl)) }
        }
    }

    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text(stringResource(R.string.theme_label)) },
            text = {
                Column {
                    listOf(
                        ThemeMode.SYSTEM to R.string.theme_system,
                        ThemeMode.LIGHT to R.string.theme_light,
                        ThemeMode.DARK to R.string.theme_dark,
                    ).forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    showThemePicker = false
                                    vm.setTheme(mode)
                                    onThemeChanged(mode)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = theme == mode,
                                onClick = null,
                            )
                            Spacer(Modifier.width(TbDimens.spaceSm))
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.workspace_restore_title)) },
            text = { Text(stringResource(R.string.workspace_restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    scope.launch {
                        when (val n = vm.restoreWorkspaces()) {
                            -1 -> snack(snRestoreFailed)
                            0 -> snack(snRestoreNone)
                            else -> snack(
                                context.getString(R.string.workspace_restore_done, n)
                            )
                        }
                    }
                }) { Text(stringResource(R.string.workspace_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_downloads)) },
            text = { Text(stringResource(R.string.settings_clear_downloads_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    val deleted = run {
                        val dir = com.tenderbase.app.DocumentStore.dir(context)
                        val files = dir.listFiles { f ->
                            f.isFile && f.extension.lowercase() in
                                com.tenderbase.app.DocumentStore.DOCUMENT_EXTENSIONS
                        } ?: emptyArray()
                        files.count { runCatching { it.delete() }.getOrDefault(false) }
                    }
                    snack(context.getString(R.string.downloads_cleared_n, deleted))
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}


// ================================================================= HELPERS

/** Compact shared chrome: top bar + content column (no wasted header space). */
@Composable
fun ScaffoldishColumn(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TbTopBar(title = title, onBack = onBack, subtitle = subtitle)
        Column(Modifier.fillMaxSize().navigationBarsPadding(), content = content)
    }
}

private fun openDownloadedFile(
    context: android.content.Context,
    file: java.io.File,
    onFail: () -> Unit,
) {
    val ok = try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext == "pdf") "application/pdf" else "application/octet-stream"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
    if (!ok) onFail()
}

private fun shareDownloadedFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.cd_share_file))
        )
    } catch (_: Exception) {
        // No chooser available — nothing else we can do from the VM layer.
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent) }
}
