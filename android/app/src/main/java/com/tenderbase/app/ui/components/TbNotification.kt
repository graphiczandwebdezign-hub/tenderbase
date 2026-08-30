@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tenderbase.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.NotificationGroups
import com.tenderbase.app.NotificationKind
import com.tenderbase.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification centre row: type icon + type label + tender context + time.
 * Unread state is a dot + text label — never colour alone (spec §19).
 */
@Composable
fun TbNotificationRow(
    title: String,
    body: String,
    timestamp: Long,
    isRead: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = NotificationGroups.kindOf(title, body)
    val timeLabel = rememberTimeLabel(timestamp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isRead) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconForKind(kind),
                    contentDescription = null,
                    tint = if (isRead) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(TbDimens.iconSm),
                )
            }
            Spacer(Modifier.width(TbDimens.spaceMd))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        labelForKind(kind),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(TbDimens.spaceSm))
                    if (!isRead) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            " " + stringResource(R.string.unread_suffix),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.size(3.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.view_tender),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberTimeLabel(timestamp: Long): String {
    val fmt = rememberTimestampFormat(timestamp)
    return fmt.format(Date(timestamp))
}

@Composable
private fun rememberTimestampFormat(timestamp: Long): SimpleDateFormat {
    val now = System.currentTimeMillis()
    val sameDay = NotificationGroups.bucketOf(timestamp, now) == NotificationBucketSameDay
    return remember(sameDay) {
        if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault())
        else SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
    }
}

private val NotificationBucketSameDay = com.tenderbase.app.NotificationBucket.TODAY

@Composable
private fun labelForKind(kind: NotificationKind): String = stringResource(
    when (kind) {
        NotificationKind.NEW_MATCH -> R.string.kind_new_match
        NotificationKind.DEADLINE -> R.string.kind_closing_soon
        NotificationKind.DEADLINE_CHANGED -> R.string.kind_deadline_changed
        NotificationKind.SAVED_UPDATED -> R.string.kind_saved_updated
        NotificationKind.DOCUMENT -> R.string.kind_document
        NotificationKind.GENERAL -> R.string.notifications_title
    }
)

private fun iconForKind(kind: NotificationKind) = when (kind) {
    NotificationKind.NEW_MATCH -> Icons.Filled.NewReleases
    NotificationKind.DEADLINE -> Icons.Filled.CalendarMonth
    NotificationKind.DEADLINE_CHANGED -> Icons.Filled.Sync
    NotificationKind.SAVED_UPDATED -> Icons.Filled.Notifications
    NotificationKind.DOCUMENT -> Icons.Filled.Description
    NotificationKind.GENERAL -> Icons.Filled.Notifications
}
