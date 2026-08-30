@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tenderbase.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.BidPack
import com.tenderbase.app.R
import com.tenderbase.app.Tender
import com.tenderbase.app.TenderTaxonomy
import com.tenderbase.app.ui.theme.TbDimens

/**
 * The discovery result card — one scannable answer per question:
 * category → title → organisation → reference → location → deadline.
 * Long-press shares (via [onLongClick]) so the card stays flat and dense.
 */
@Composable
fun TenderCard(
    tender: Tender,
    isSaved: Boolean,
    onClick: () -> Unit,
    onSaveToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showSource: Boolean = true,
    compact: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val category = tender.category ?: tender.categories.firstOrNull()
    val location = listOfNotNull(tender.province, tender.municipality).joinToString(" · ")
    val saveDesc = stringResource(if (isSaved) R.string.cd_unsave else R.string.cd_save)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = TbDimens.screenHMargin,
                vertical = if (compact) 3.dp else 5.dp,
            )
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = stringResource(R.string.open_tender),
                onLongClickLabel = onLongClick?.let { stringResource(R.string.cd_share_tender) },
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(
                horizontal = TbDimens.cardPaddingH,
                vertical = if (compact) 10.dp else TbDimens.cardPaddingV,
            )
        ) {
            // [CATEGORY]        source  ☆
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (category != null) {
                    TbPill(
                        text = TenderTaxonomy.displayName(category),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(TbDimens.spaceSm))
                if (showSource && tender.source != null) {
                    Text(
                        tender.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onSaveToggle,
                    modifier = Modifier
                        .size(TbDimens.touchMin - 8.dp)
                        .semantics { contentDescription = saveDesc },
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = null,
                        tint = if (isSaved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Title — the strongest text on the card.
            Text(
                tender.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TbDimens.spaceSm),
            )

            // Organisation second, reference smaller below it.
            Text(
                tender.organisation ?: stringResource(R.string.unknown_org),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            val refLine = listOfNotNull(
                tender.reference?.let { stringResource(R.string.ref_prefix, it) },
                tender.tenderType?.uppercase(),
            )
            if (refLine.isNotEmpty()) {
                Text(
                    refLine.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }

            if (location.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(TbDimens.spaceXs))
                    Text(
                        location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Deadline — visually prominent: text + icon + colour.
            DeadlineBlock(
                tender = tender,
                modifier = Modifier.padding(top = TbDimens.spaceSm),
            )

            if (footer != null) {
                Spacer(Modifier.height(TbDimens.spaceSm))
                footer()
            }
        }
    }
}

/**
 * Saved-list card: same scan order plus a bid-preparation progress footer and
 * a note indicator (spec §11). Star acts as remove-from-saved.
 */
@Composable
fun SavedTenderCard(
    tender: Tender,
    done: Int,
    total: Int,
    hasNote: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val fraction = animateFloatAsState(
        if (total <= 0) 0f else done.toFloat() / total.toFloat(),
        label = "bid-progress",
    )
    TenderCard(
        tender = tender,
        isSaved = true,
        onClick = onClick,
        onSaveToggle = onRemove,
        onLongClick = onLongClick,
        modifier = modifier,
        showSource = false,
        footer = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceSm),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.bid_preparation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(TbDimens.spaceSm))
                    LinearProgressIndicator(
                        progress = { fraction.value },
                        modifier = Modifier
                            .height(6.dp)
                            .width(56.dp)
                            .clip(MaterialTheme.shapes.extraLarge),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                    Spacer(Modifier.width(TbDimens.spaceSm))
                    Text(
                        if (total > 0) BidPack.progressLabel(done, total)
                        else stringResource(R.string.checklist_empty),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasNote) {
                        Spacer(Modifier.width(TbDimens.spaceSm))
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.has_note),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
    )
}

/** Compact one-line urgency row for dashboard-style sections. */
@Composable
fun DeadlineRow(
    tender: Tender,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = TbDimens.spaceMd, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tender.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(tender.organisation, tender.province)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(TbDimens.spaceSm))
        val days = com.tenderbase.app.DateUtils.daysUntilClosing(
            tender.closingAt, tender.closingDate
        )
        val tier = com.tenderbase.app.DeadlineStatus.tierOf(days, tender.deadlineState)
        DeadlineBadge(
            tier = tier,
            label = com.tenderbase.app.DeadlineStatus.label(days, tender.deadlineState),
            small = true,
        )
    }
}


