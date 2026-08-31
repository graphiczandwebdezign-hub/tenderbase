@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.tenderbase.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tenderbase.app.R
import com.tenderbase.app.UserErrorKind
import com.tenderbase.app.ui.theme.TbDimens

// ------------------------------------------------------------------ top bars

/** Standard screen top bar: brand mark, title, optional back + actions. */
@Composable
fun TbTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = TbDimens.spaceMd, vertical = TbDimens.spaceSm)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                } else {
                    TbBrandMark()
                }
                Spacer(Modifier.width(TbDimens.spaceSm))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceXs),
                    content = actions,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (onBack == null) 38.dp else 0.dp),
                )
            }
        }
    }
}

/** TenderBase logo mark — small, flat, on-brand. */
@Composable
fun TbBrandMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 30.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.6f))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "TB",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
fun TbSettingsIconAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.more_settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ buttons

@Composable
fun TbPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(TbDimens.iconSm))
            Spacer(Modifier.width(TbDimens.spaceSm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TbSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(TbDimens.iconSm))
            Spacer(Modifier.width(TbDimens.spaceSm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TbOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon, contentDescription = null,
                modifier = Modifier.size(TbDimens.iconSm),
            )
            Spacer(Modifier.width(TbDimens.spaceSm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TbTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.height(TbDimens.touchMin)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ------------------------------------------------------------------ chips

/** Filter chip: selection is shown by checkmark + tinted container (text too). */
@Composable
fun TbFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        },
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** Removable active-filter chip (shown at the top of search results). */
@Composable
fun TbRemovableChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        modifier = modifier,
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.cd_chip_remove, label),
                modifier = Modifier.size(16.dp),
            )
        },
        label = {
            Text(
                label, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** Tiny inline pill used for metadata (category, reference, source). */
@Composable
fun TbPill(
    text: String,
    container: Color,
    onContainer: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    showUppercase: Boolean = true,
) {
    Surface(
        modifier = modifier,
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(TbDimens.spaceXs))
            }
            Text(
                if (showUppercase) text.uppercase() else text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------ sections

/** Overline section header used across detail/saved/settings screens. */
@Composable
fun TbSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin)
            .padding(top = TbDimens.spaceXl, bottom = TbDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

@Composable
fun TbMetaRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ search field

/**
 * The primary search field (discovery + search screen). Real TextField when
 * [active], tappable mock otherwise; search IME action commits.
 */
@Composable
fun TbSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_tap_hint),
    onImeSearch: () -> Unit = {},
    active: Boolean = false,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (active) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.cd_clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(TbDimens.iconSm),
                        )
                    }
                }
                if (trailing != null) trailing()
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onImeSearch() }),
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.cd_search),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(TbDimens.spaceSm))
                Text(
                    query.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) trailing()
            }
        }
    }
}

// ------------------------------------------------------------------ states

@Composable
fun TbEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimaryClick: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.spaceXxl, vertical = TbDimens.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(TbDimens.iconLg),
            )
        }
        Spacer(Modifier.height(TbDimens.spaceLg))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(TbDimens.spaceSm))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimaryClick != null) {
            Spacer(Modifier.height(TbDimens.spaceLg))
            TbPrimaryButton(text = primaryLabel, onClick = onPrimaryClick)
        }
        if (secondaryLabel != null && onSecondaryClick != null) {
            Spacer(Modifier.height(TbDimens.spaceSm))
            TbTextButton(text = secondaryLabel, onClick = onSecondaryClick)
        }
    }
}

/** Human error surface: never shows exception text — see ErrorMessages. */
@Composable
fun TbErrorState(
    kind: UserErrorKind,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val title: String
    val body: String
    when (kind) {
        UserErrorKind.OFFLINE -> {
            title = stringResource(R.string.error_offline_title)
            body = stringResource(R.string.error_offline_body)
        }
        UserErrorKind.SERVER_UNAVAILABLE -> {
            title = stringResource(R.string.error_server_title)
            body = stringResource(R.string.error_server_body)
        }
        UserErrorKind.SECURITY -> {
            title = stringResource(R.string.download_failed_title)
            body = stringResource(R.string.download_failed_body)
        }
        UserErrorKind.NOT_FOUND -> {
            title = stringResource(R.string.detail_not_found_title)
            body = stringResource(R.string.detail_not_found_body)
        }
        UserErrorKind.ACCESS_DENIED -> {
            title = stringResource(R.string.access_denied_title)
            body = stringResource(R.string.access_denied_body)
        }
        UserErrorKind.GENERIC -> {
            title = stringResource(R.string.error_generic_title)
            body = stringResource(R.string.error_generic_body)
        }
    }
    TbEmptyState(
        icon = if (kind == UserErrorKind.OFFLINE) Icons.Filled.Warning else Icons.Filled.Refresh,
        title = title,
        body = body,
        modifier = modifier,
        primaryLabel = stringResource(R.string.retry),
        onPrimaryClick = onRetry,
        secondaryLabel = secondaryLabel,
        onSecondaryClick = onSecondary,
    )
}

/** Pulsing placeholder card shown while the first page loads. */
@Composable
fun TbSkeletonCard(modifier: Modifier = Modifier, lines: Int = 3) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val a by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    val shape = RoundedCornerShape(TbDimens.cardCorner)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin, vertical = 5.dp)
            .alpha(a),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(TbDimens.spaceLg)) {
            Box(
                Modifier
                    .size(width = 72.dp, height = 18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.height(TbDimens.spaceSm))
            Box(
                Modifier
                    .fillMaxWidth(0.9f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            repeat(lines) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(if (it == 0) 0.7f else if (it == lines - 1) 0.45f else 0.85f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            Spacer(Modifier.height(TbDimens.spaceMd))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

/** Thin banner shown when the list is rendering cached/offline data. */
@Composable
fun TbOfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TbDimens.screenHMargin, vertical = TbDimens.spaceSm),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(TbDimens.iconSm))
            Spacer(Modifier.width(TbDimens.spaceSm))
            Text(
                stringResource(R.string.offline_banner),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Flow-wrap container so chips can never clip horizontally (spec #6/#20). */
@Composable
fun TbChipFlow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TbDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(TbDimens.spaceXs),
        content = content,
    )
}
