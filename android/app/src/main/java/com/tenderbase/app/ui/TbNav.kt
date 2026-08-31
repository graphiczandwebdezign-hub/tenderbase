@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tenderbase.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tenderbase.app.R

/** Primary destinations (spec §13). "More" holds the secondary IA. */
enum class TbTab { HOME, SEARCH, SAVED, ALERTS, MORE }

/**
 * Navigation callbacks handed to screens; kept explicit (no composition
 * locals) so every screen documents what it can navigate to.
 */
class TbNav(
    val switchTab: (TbTab) -> Unit,
    val openDetail: (Int) -> Unit,
    val openDeadlines: () -> Unit,
    val openDownloads: () -> Unit,
    val openSavedSearches: () -> Unit,
    val openPreferences: (String) -> Unit,
    val openSettings: () -> Unit,
)

@Composable
fun TbBottomBar(
    selected: TbTab,
    onSelect: (TbTab) -> Unit,
    unreadCount: Int,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier, containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = selected == TbTab.HOME,
            onClick = { onSelect(TbTab.HOME) },
            icon = {
                Icon(
                    if (selected == TbTab.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.nav_home)) },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = selected == TbTab.SEARCH,
            onClick = { onSelect(TbTab.SEARCH) },
            icon = {
                Icon(
                    if (selected == TbTab.SEARCH) Icons.Filled.Search else Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.cd_search),
                )
            },
            label = { Text(stringResource(R.string.nav_search)) },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = selected == TbTab.SAVED,
            onClick = { onSelect(TbTab.SAVED) },
            icon = {
                Icon(
                    if (selected == TbTab.SAVED) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.nav_saved)) },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = selected == TbTab.ALERTS,
            onClick = { onSelect(TbTab.ALERTS) },
            icon = {
                BadgedBox(
                    badge = {
                        if (unreadCount > 0) {
                            Badge { Text(if (unreadCount > 99) "99+" else "$unreadCount") }
                        }
                    }
                ) {
                    Icon(
                        if (selected == TbTab.ALERTS) Icons.Filled.Notifications
                        else Icons.Outlined.Notifications,
                        contentDescription = stringResource(R.string.notifications_title),
                    )
                }
            },
            label = { Text(stringResource(R.string.nav_notifications)) },
            alwaysShowLabel = true,
        )
        NavigationBarItem(
            selected = selected == TbTab.MORE,
            onClick = { onSelect(TbTab.MORE) },
            icon = {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.nav_more))
            },
            label = { Text(stringResource(R.string.nav_more)) },
            alwaysShowLabel = true,
        )
    }
}
