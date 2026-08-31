@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tenderbase.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.CalendarContract
import android.webkit.MimeTypeMap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tenderbase.app.BidPack
import com.tenderbase.app.DateUtils
import com.tenderbase.app.R
import kotlinx.coroutines.launch
import com.tenderbase.app.TenderActions
import com.tenderbase.app.TenderTaxonomy
import com.tenderbase.app.ui.components.DeadlineHeroCard
import com.tenderbase.app.ui.components.BidProgressCard
import com.tenderbase.app.ui.components.TbChecklistRow
import com.tenderbase.app.ui.components.TbNoteCard
import com.tenderbase.app.ui.components.TbEmptyState
import com.tenderbase.app.ui.components.TbErrorState
import com.tenderbase.app.ui.components.TbMetaRow
import com.tenderbase.app.ui.components.TbOfflineBanner
import com.tenderbase.app.ui.components.TbPill
import com.tenderbase.app.ui.components.TbSectionHeader
import com.tenderbase.app.ui.components.TbSecondaryButton
import com.tenderbase.app.ui.components.TbSkeletonCard
import com.tenderbase.app.ui.components.TbTopBar
import com.tenderbase.app.ui.theme.TbDimens
import com.tenderbase.app.ui.vm.DetailViewModel
import com.tenderbase.app.ui.vm.DocDownloadState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tender detail — the professional bid-preparation workspace (spec §8–10).
 * One scrolling column: identity → deadline hero → facts → documents →
 * bid workspace → source.
 */
@Composable
fun DetailScreen(
    tenderId: Int,
    onBack: () -> Unit,
) {
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(tenderId) { vm.start(tenderId) }

    val state by vm.state.collectAsStateWithLifecycle()
    val docStates by vm.docStates.collectAsStateWithLifecycle()
    val note by vm.noteText.collectAsStateWithLifecycle()
    val checklist by vm.checklist.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val bidSubject = stringResource(R.string.bid_pack_subject, state.tender?.title ?: "")
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showAddItem by rememberSaveable { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<com.tenderbase.app.ChecklistItemEntity?>(null) }
    var deleteItem by remember { mutableStateOf<com.tenderbase.app.ChecklistItemEntity?>(null) }

    Scaffold(
        snackbarHost = {
            androidx.compose.foundation.layout.Box(
                Modifier.navigationBarsPadding()
            ) { SnackbarHost(snackbar) }
        },
        topBar = {
            TbTopBar(
                title = stringResource(R.string.detail_title),
                onBack = onBack,
                actions = {
                    val t = state.tender
                    IconButton(onClick = {
                        val text = vm.shareSummary()
                        if (t != null) sharePlainText(context, t.title, text)
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.cd_share_tender),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.toggleSave() }) {
                        Icon(
                            imageVector = if (state.saved) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = stringResource(
                                if (state.saved) R.string.cd_unsave else R.string.cd_save
                            ),
                            tint = if (state.saved) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        val t = state.tender
        when {
            state.loading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = TbDimens.spaceXl),
            ) {
                repeat(3) { TbSkeletonCard(lines = if (it == 0) 5 else 2) }
            }
            t == null -> Box(Modifier.fillMaxSize().padding(padding)) {
                TbErrorState(
                    kind = state.errorKind ?: com.tenderbase.app.UserErrorKind.GENERIC,
                    onRetry = { vm.load() },
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = TbDimens.spaceXxl,
                ),
            ) {
                if (state.offlineCopy) {
                    item { TbOfflineBanner() }
                }

                // ------------------------------------------------ identity
                item(key = "header") {
                    val category = t.category ?: t.categories.firstOrNull()
                    Column(
                        Modifier.padding(
                            start = TbDimens.screenHMargin,
                            end = TbDimens.screenHMargin,
                            top = TbDimens.spaceMd,
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (category != null) {
                                TbPill(
                                    text = TenderTaxonomy.displayName(category),
                                    container = MaterialTheme.colorScheme.secondaryContainer,
                                    onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(Modifier.width(TbDimens.spaceSm))
                            t.tenderType?.let {
                                TbPill(
                                    text = it,
                                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (!t.source.isNullOrBlank()) {
                                Text(
                                    t.source!!,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.height(TbDimens.spaceSm))
                        Text(
                            t.title,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(TbDimens.spaceSm))
                        Text(
                            t.organisation
                                ?: stringResource(R.string.unknown_org),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val sub = listOfNotNull(
                            t.reference?.let { stringResource(R.string.ref_prefix, it) },
                            listOfNotNull(t.province, t.municipality).joinToString(" · ")
                                .takeIf { it.isNotEmpty() },
                        )
                        if (sub.isNotEmpty()) {
                            Text(
                                sub.joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ------------------------------------------------ deadline
                item(key = "deadline") {
                    Column(
                        Modifier.padding(
                            horizontal = TbDimens.screenHMargin,
                            vertical = TbDimens.spaceLg,
                        )
                    ) {
                        DeadlineHeroCard(tender = t)
                        Spacer(Modifier.height(TbDimens.spaceSm))
                        Row(horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceSm)) {
                            TbSecondaryButton(
                                text = stringResource(R.string.add_to_calendar),
                                onClick = {
                                    addDeadlineToCalendar(context, t) { msg ->
                                        scope.launch { snackbar.showSnackbar(msg) }
                                    }
                                },
                                leadingIcon = Icons.Filled.Event,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ------------------------------------------------ overview
                item(key = "overview-hdr") {
                    TbSectionHeader(label = stringResource(R.string.section_overview))
                }
                item(key = "overview") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TbDimens.screenHMargin)
                    ) {
                        val rows = buildList {
                            t.status?.let { add(stringResource(R.string.label_status) to it.replace('_', ' ')) }
                            t.advertisedDate?.let {
                                add(
                                    stringResource(R.string.label_published)
                                        to prettyIso(it)
                                )
                            }
                            add(
                                stringResource(R.string.label_closing_date)
                                    to DateUtils.dateTimeLabel(t.closingAt, t.closingDate)
                            )
                            t.submissionMethod?.let {
                                add(stringResource(R.string.label_submission) to it)
                            }
                            t.source?.let { add(stringResource(R.string.section_source) to it) }
                        }
                        rows.forEach { (label, value) ->
                            TbMetaRow(label, value)
                        }
                    }
                }

                // --------------------------------------------- description
                if (!t.description.isNullOrBlank()) {
                    item(key = "desc-hdr") {
                        TbSectionHeader(label = stringResource(R.string.section_description))
                    }
                    item(key = "desc") {
                        ExpandableText(t.description!!, maxLines = 6)
                    }
                }

                // ----------------------------------------------- amendments
                if (t.amendments.isNotEmpty()) {
                    item(key = "amend-hdr") {
                        TbSectionHeader(label = stringResource(R.string.label_amendments))
                    }
                    items(count = t.amendments.size, key = { "amend-$it" }) { i ->
                        val a = t.amendments[i]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = TbDimens.screenHMargin, vertical = 6.dp)
                        ) {
                            Text(
                                a.fieldChanged.replace('_', ' ')
                                    .replaceFirstChar { it.uppercase() } +
                                    (a.detectedAt?.let { " · ${prettyIso(it)}" } ?: ""),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(
                                    R.string.amendment_change,
                                    a.oldValue ?: "—",
                                    a.newValue ?: "—",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ------------------------------------------------ documents
                item(key = "docs-hdr") {
                    TbSectionHeader(
                        label = stringResource(R.string.label_documents),
                        trailing = {
                            if (t.documents.isEmpty()) null else {
                                Text(
                                    "${t.documents.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (t.documents.isEmpty()) {
                    item(key = "docs-empty") {
                        Text(
                            stringResource(R.string.no_documents_for_tender),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = TbDimens.screenHMargin,
                                vertical = TbDimens.spaceSm,
                            ),
                        )
                    }
                } else {
                    val groups = BidPack.groupDocuments(t.documents)
                    groups.forEach { group ->
                        item(key = "docgrp-${group.title}") {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = TbDimens.screenHMargin,
                                    end = TbDimens.screenHMargin,
                                    bottom = TbDimens.spaceXs,
                                ),
                            )
                        }
                        items(count = group.documents.size, key = { "doc-${group.title}-$it" }) { idx ->
                            val doc = group.documents[idx]
                            com.tenderbase.app.ui.components.TenderDocumentRow(
                                doc = doc,
                                state = docStates[doc.url] ?: DocDownloadState.Idle,
                                onDownload = { vm.download(doc) },
                                onRetry = { vm.retryDownload(doc) },
                                onOpen = {
                                    val st = docStates[doc.url]
                                    if (st is DocDownloadState.Done &&
                                        !openDocument(context, st.file)
                                    ) {
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                context.getString(R.string.open_failed)
                                            )
                                        }
                                    }
                                },
                                onOpenSource = {
                                    t.sourceUrl?.let { openUrl(context, it) }
                                },
                            )
                        }
                    }
                }

                // -------------------------------------------- bid workspace
                item(key = "ws-hdr") {
                    TbSectionHeader(
                        label = stringResource(R.string.workspace_title),
                        trailing = {
                            TextButton(onClick = { showAddItem = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(TbDimens.spaceXs))
                                Text(stringResource(R.string.add_item))
                            }
                        },
                    )
                }
                item(key = "ws-progress") {
                    BidProgressCard(
                        done = checklist.count { it.isDone },
                        total = checklist.size,
                        onShareBidPack = {
                            sharePlainText(context, bidSubject, vm.bidPackText())
                        },
                    )
                }
                items(count = checklist.size, key = { "ck-${checklist[it].id}" }) { idx ->
                    val item = checklist[idx]
                    TbChecklistRow(
                        item = item,
                        isFirst = idx == 0,
                        isLast = idx == checklist.size - 1,
                        onToggle = { checked -> vm.toggleChecklistDone(item.id, checked) },
                        onEdit = { editItem = item },
                        onDelete = { deleteItem = item },
                        onMoveUp = { vm.moveChecklistItem(item.id, up = true) },
                        onMoveDown = { vm.moveChecklistItem(item.id, up = false) },
                    )
                }
                item(key = "ws-note") {
                    Spacer(Modifier.height(TbDimens.spaceSm))
                    TbNoteCard(note = note, onEdit = { showNoteDialog = true })
                }

                // --------------------------------------------------- source
                if (!t.sourceUrl.isNullOrBlank()) {
                    item(key = "source-hdr") {
                        TbSectionHeader(label = stringResource(R.string.section_source))
                    }
                    item(key = "source") {
                        Column(
                            Modifier.padding(horizontal = TbDimens.screenHMargin),
                        ) {
                            Text(
                                t.source ?: t.sourceUrl!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(TbDimens.spaceSm))
                            TbSecondaryButton(
                                text = stringResource(R.string.open_on_etenders),
                                onClick = { openUrl(context, t.sourceUrl!!) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- dialogs

    if (showNoteDialog) {
        NoteEditDialog(
            initial = note.orEmpty(),
            onDismiss = { showNoteDialog = false },
            onSave = { text ->
                showNoteDialog = false
                vm.saveNote(text)
            },
            onClear = {
                showNoteDialog = false
                vm.saveNote("")
            },
        )
    }
    if (showAddItem) {
        SingleFieldDialog(
            title = stringResource(R.string.checklist_add),
            hint = stringResource(R.string.checklist_add_hint),
            initial = "",
            onDismiss = { showAddItem = false },
            onConfirm = {
                vm.addChecklistItem(it)
                showAddItem = false
            },
        )
    }
    editItem?.let { item ->
        SingleFieldDialog(
            title = stringResource(R.string.edit_item),
            hint = stringResource(R.string.checklist_add_hint),
            initial = item.label,
            onDismiss = { editItem = null },
            onConfirm = {
                vm.renameChecklistItem(item.id, it)
                editItem = null
            },
        )
    }
    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text(stringResource(R.string.checklist_delete_title)) },
            text = { Text(item.label, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChecklistItem(item.id)
                    deleteItem = null
                }) { Text(stringResource(R.string.checklist_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------------ widgets

@Composable
private fun ExpandableText(text: String, maxLines: Int) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = TbDimens.screenHMargin)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.length > maxLines * 90) { // cheap heuristic; hide the toggle for short bodies
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 0.dp, vertical = 4.dp
                ),
            ) {
                Text(
                    stringResource(if (expanded) R.string.read_less else R.string.read_more),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun NoteEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_edit_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(stringResource(R.string.note_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial.isNotBlank()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.note_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun SingleFieldDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ----------------------------------------------------------------- intents

private fun sharePlainText(context: Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(intent, subject))
    } catch (_: ActivityNotFoundException) {
        // Nothing to share to; stay silent — the app offers no other channel.
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun openDocument(context: Context, file: File): Boolean {
    return try {
        val uri: Uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext == "pdf") "application/pdf" else "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

private fun addDeadlineToCalendar(
    context: Context,
    t: com.tenderbase.app.Tender,
    message: (String) -> Unit,
) {
    val slot = TenderActions.calendarSlot(t)
    if (slot == null) {
        message(context.getString(R.string.calendar_no_deadline))
        return
    }
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, slot.beginMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, slot.endMillis)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, slot.allDay)
        putExtra(CalendarContract.Events.TITLE, context.getString(R.string.calendar_deadline_title, t.title))
        putExtra(CalendarContract.Events.DESCRIPTION, TenderActions.shareSummary(t))
        t.organisation?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        putExtra(
            CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.AVAILABILITY_BUSY,
        )
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        message(context.getString(R.string.calendar_no_app))
    }
}

private fun prettyIso(iso: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
    if (parsed == null) iso
    else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(parsed)
} catch (_: Exception) {
    iso
}
