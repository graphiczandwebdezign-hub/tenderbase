@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tenderbase.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.DateUtils
import com.tenderbase.app.DeadlineStatus
import com.tenderbase.app.DeadlineTier
import com.tenderbase.app.R
import com.tenderbase.app.Tender
import com.tenderbase.app.ui.theme.LocalTbUrgency
import com.tenderbase.app.ui.theme.TbDimens

/** Icon paired with each deadline tier — status is never colour-only. */
fun iconForTier(tier: DeadlineTier): ImageVector = when (tier) {
    DeadlineTier.URGENT -> Icons.Filled.Warning
    DeadlineTier.CLOSING_SOON -> Icons.Filled.DateRange
    DeadlineTier.UPCOMING -> Icons.Filled.DateRange
    DeadlineTier.SAFE -> Icons.Filled.CheckCircle
    DeadlineTier.CLOSED -> Icons.Filled.Close
    DeadlineTier.NONE -> Icons.Filled.Info
}

/**
 * Compact urgency badge: icon + words + tier container. Used in list rows
 * and as the countdown chip in the detail header.
 */
@Composable
fun DeadlineBadge(
    tier: DeadlineTier,
    label: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val urgency = LocalTbUrgency.current.styleOf(tier)
    Surface(
        modifier = modifier,
        color = urgency.bg,
        contentColor = urgency.fg,
        shape = RoundedCornerShape(if (small) 8.dp else 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = if (small) 6.dp else 8.dp,
                vertical = if (small) 3.dp else 5.dp,
            ),
        ) {
            Icon(
                iconForTier(tier),
                contentDescription = null,
                modifier = Modifier.size(if (small) 12.dp else 14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = if (small) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The prominent deadline block inside a tender card: relative urgency on the
 * left, exact date/time on the right. Both carry text — the tint is support,
 * not the message.
 */
@Composable
fun DeadlineBlock(
    tender: Tender,
    modifier: Modifier = Modifier,
) {
    val days = DateUtils.daysUntilClosing(tender.closingAt, tender.closingDate)
    val tier = DeadlineStatus.tierOf(days, tender.deadlineState)
    val urgency = LocalTbUrgency.current.styleOf(tier)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = urgency.bg,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconForTier(tier),
                    contentDescription = null,
                    tint = urgency.fg,
                    modifier = Modifier.size(TbDimens.iconSm),
                )
                Spacer(Modifier.width(TbDimens.spaceSm))
                Text(
                    DeadlineStatus.label(days, tender.deadlineState),
                    style = MaterialTheme.typography.titleSmall,
                    color = urgency.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(TbDimens.spaceSm))
            Text(
                DateUtils.dateTimeLabel(tender.closingAt, tender.closingDate),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = urgency.fg.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Big deadline card for the detail screen header. */
@Composable
fun DeadlineHeroCard(
    tender: Tender,
    modifier: Modifier = Modifier,
) {
    val days = DateUtils.daysUntilClosing(tender.closingAt, tender.closingDate)
    val tier = DeadlineStatus.tierOf(days, tender.deadlineState)
    val urgency = LocalTbUrgency.current.styleOf(tier)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tier accent bar — decorative; the text carries the meaning.
            Box(
                Modifier
                    .width(4.dp)
                    .height(52.dp)
                    .background(urgency.accent, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(TbDimens.spaceMd))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.deadline_overline).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    DateUtils.prettyDate(tender.closingAt, tender.closingDate),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val time = DateUtils.closingTimeLabel(tender.closingAt)
                val weekday = DateUtils.weekdayLabel(tender.closingAt, tender.closingDate)
                Text(
                    listOfNotNull(weekday, time?.let { "$it local time" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(TbDimens.spaceSm))
            DeadlineBadge(
                tier = tier,
                label = DeadlineStatus.label(days, tender.deadlineState),
                small = true,
            )
        }
    }
}
