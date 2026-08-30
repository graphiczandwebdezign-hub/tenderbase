@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.tenderbase.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.BidPack
import com.tenderbase.app.ChecklistItemEntity
import com.tenderbase.app.R
import com.tenderbase.app.TenderActions
import com.tenderbase.app.TenderDoc
import com.tenderbase.app.ui.theme.TbDimens
import com.tenderbase.app.ui.vm.DocDownloadState

// ---------------------------------------------------------------- documents

/**
 * One document row: type chip, title, "PDF · 2.4 MB", download/open action.
 * Failure renders a human note + Retry / Open source — never exception text.
 */
@Composable
fun TenderDocumentRow(
    doc: TenderDoc,
    state: DocDownloadState,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failed = state is DocDownloadState.Failed
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin, vertical = 3.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(TbDimens.spaceMd))
                Column(Modifier.weight(1f)) {
                    Text(
                        doc.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        doc.mime?.substringAfter('/')?.uppercase()
                            ?: doc.url.substringAfterLast('.', "").takeIf { it.length in 1..4 }
                                ?.uppercase()
                            ?: "FILE",
                        TenderActions.formatFileSize(doc.fileSize),
                    ).joinToString(" · ")
                    Text(
                        if (state is DocDownloadState.Done) "$meta · " +
                            stringResource(R.string.download_saved) else meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(TbDimens.spaceSm))
                when (state) {
                    is DocDownloadState.Idle -> IconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(TbDimens.touchMin),
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = stringResource(R.string.download_doc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is DocDownloadState.Working -> Box(
                        Modifier.size(TbDimens.touchMin), contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                    is DocDownloadState.Done -> IconButton(
                        onClick = onOpen,
                        modifier = Modifier.size(TbDimens.touchMin),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.download_open),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is DocDownloadState.Failed -> IconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(TbDimens.touchMin),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.retry),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (failed) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.download_failed_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(TbDimens.spaceXs))
                        Text(
                            stringResource(R.string.download_failed_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceSm),
                            modifier = Modifier.padding(top = TbDimens.spaceSm),
                        ) {
                            TbSecondaryButton(
                                text = stringResource(R.string.retry),
                                onClick = onRetry,
                                leadingIcon = Icons.Filled.Refresh,
                            )
                            TbTextButton(
                                text = stringResource(R.string.open_source_tender),
                                onClick = onOpenSource,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------- bid workspace

/**
 * Bid preparation progress card: "3 of 7 complete" + animated bar + share
 * bid pack. The one place that understands workspace completion.
 */
@Composable
fun BidProgressCard(
    done: Int,
    total: Int,
    onShareBidPack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction by animateColorAsState(
        targetValue = if (total > 0 && done == total) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.primary,
        label = "progress-color",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.workspace_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (total > 0) BidPack.progressLabel(done, total)
                        else stringResource(R.string.checklist_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    if (total > 0) "${((done.toFloat() / total) * 100).toInt()}%" else "0%",
                    style = MaterialTheme.typography.titleLarge,
                    color = fraction,
                )
            }
            Spacer(Modifier.height(TbDimens.spaceSm))
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total.toFloat() else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.extraLarge),
                color = fraction,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
            )
            if (total > 0 && done == total) {
                Spacer(Modifier.height(TbDimens.spaceSm))
                Text(
                    stringResource(R.string.workspace_complete_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(TbDimens.spaceMd))
            TbSecondaryButton(
                text = stringResource(R.string.share_bid_pack),
                onClick = onShareBidPack,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Filled.Share,
            )
        }
    }
}

/** Checklist row with per-item menu (edit, reorder, delete). */
@Composable
fun TbChecklistRow(
    item: ChecklistItemEntity,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin, vertical = 2.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = item.isDone,
                onCheckedChange = { onToggle(it ?: false) },
            )
            Text(
                item.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onToggle(!item.isDone) }
                    .padding(end = 8.dp),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_item_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_item)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        enabled = !isFirst,
                        onClick = { menuOpen = false; onMoveUp() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        enabled = !isLast,
                        onClick = { menuOpen = false; onMoveDown() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_item)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Workspace note surface: preview + edit affordance. */
@Composable
fun TbNoteCard(
    note: String?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onEdit,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.bid_notes).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(TbDimens.spaceSm))
                Text(
                    note?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.note_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(TbDimens.spaceSm))
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.edit_note),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(TbDimens.iconMd),
            )
        }
    }
}
