package com.tenderbase.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tenderbase.app.ui.TbBottomBar
import com.tenderbase.app.ui.TbNav
import com.tenderbase.app.ui.TbTab
import com.tenderbase.app.ui.screens.HomeScreen
import com.tenderbase.app.ui.screens.MoreScreen
import com.tenderbase.app.ui.screens.NotificationsScreen
import com.tenderbase.app.ui.screens.SavedScreen
import com.tenderbase.app.ui.screens.SearchScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme
import com.tenderbase.app.ui.vm.DiscoveryViewModel
import com.tenderbase.app.ui.vm.NotificationsViewModel
import com.tenderbase.app.ui.vm.SavedViewModel
import kotlinx.coroutines.launch

/**
 * TenderBase home container: five primary destinations (Home, Search, Saved,
 * Alerts, More) on a bottom bar (spec §13). Discovery state lives in
 * [DiscoveryViewModel]; the Home and Search tabs share one instance, so
 * switching tabs never re-fetches.
 */
class MainActivity : AppCompatActivity() {

    private val vm: DiscoveryViewModel by viewModels()

    /** Tab state shared with [MainRoot]; updated by deep links / redirects. */
    private val tabState: MutableState<TbTab> = mutableStateOf(TbTab.HOME)

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* observed via prefs */ }

    companion object {
        /** Intent extra: a saved-search filters JSON to apply on open. */
        const val EXTRA_APPLY_FILTERS = "apply_filters_json"
        /** Intent extra: a named discovery preset ("closing_week"). */
        const val EXTRA_PRESET = "discovery_preset"
        const val PRESET_CLOSING_WEEK = "closing_week"
        /** Intent extra: which bottom tab to open ("saved" | "alerts"). */
        const val EXTRA_TAB = "initial_tab"

        fun openTabIntent(context: Context, tab: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TAB, tab)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashReporter.breadcrumb("main: onCreate (recreated=${savedInstanceState != null})")

        val consumingIntentExtras = savedInstanceState == null
        handleIntent(intent, consuming = consumingIntentExtras)
        if (consumingIntentExtras && !intent.hasExtra(EXTRA_APPLY_FILTERS) &&
            !intent.hasExtra(EXTRA_PRESET)
        ) {
            vm.start()
        }

        maybeRequestNotificationPermission()

        setContent {
            TenderBaseTheme {
                MainRoot(
                    discoveryVm = vm,
                    tabState = tabState,
                    openDetail = { id -> openDetail(id) },
                    onShare = { t -> shareTender(t) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, consuming = true)
    }

    private fun handleIntent(intent: Intent?, consuming: Boolean) {
        val extras = intent?.extras ?: return
        when (extras.getString(EXTRA_TAB)) {
            "saved" -> tabState.value = TbTab.SAVED
            "alerts" -> tabState.value = TbTab.ALERTS
            "search" -> tabState.value = TbTab.SEARCH
        }
        if (!consuming) return
        val applyJson = extras.getString(EXTRA_APPLY_FILTERS)
        when {
            applyJson != null -> vm.applyFiltersJson(applyJson)
            extras.getString(EXTRA_PRESET) != null -> vm.applyPresetClosingWeek()
        }
    }

    private fun openDetail(id: Int) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_ID, id)
        )
    }

    private fun shareTender(t: Tender) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, t.title)
            putExtra(Intent.EXTRA_TEXT, TenderActions.shareSummary(t))
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
        }
    }

    /**
     * Ask for notification permission once (Android 13+). The "asked" flag is
     * owned by [TenderRepository] and is also set by onboarding's own prompt,
     * so the user is never greeted with two system dialogs back to back
     * (audit finding H2).
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val repo = TenderRepository(applicationContext)
        if (repo.notifPermissionAsked()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        repo.setNotifPermissionAsked()
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

// ---------------------------------------------------------------- root UI

@Composable
private fun MainRoot(
    discoveryVm: DiscoveryViewModel,
    tabState: MutableState<TbTab>,
    openDetail: (Int) -> Unit,
    onShare: (Tender) -> Unit,
) {
    val tab = tabState.value
    val unread by discoveryVm.unreadCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snack: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    val savedVm: SavedViewModel = viewModel()
    val alertsVm: NotificationsViewModel = viewModel()
    val context = LocalContext.current

    // "What's new" — shown only after an update (fresh installs stay silent).
    var whatsNew by remember { mutableStateOf<Changelog.ReleaseNotes?>(null) }
    LaunchedEffect(Unit) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val prefs = context.getSharedPreferences("tenderbase_prefs", 0)
        val lastSeen = prefs.getString("last_seen_version", null)
        if (version != null && Changelog.shouldShow(version, lastSeen)) {
            whatsNew = Changelog.notesFor(version)
        }
        prefs.edit().putString("last_seen_version", version).apply()
    }

    val nav = TbNav(
        switchTab = { tabState.value = it },
        openDetail = openDetail,
        openDeadlines = { context.startActivity(Intent(context, DeadlinesActivity::class.java)) },
        openDownloads = { context.startActivity(Intent(context, DownloadsActivity::class.java)) },
        openSavedSearches = {
            context.startActivity(Intent(context, SavedSearchesActivity::class.java))
        },
        openPreferences = { type ->
            context.startActivity(
                Intent(context, PreferencesActivity::class.java)
                    .putExtra(PreferencesActivity.EXTRA_TYPE, type)
            )
        },
        openSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },  // Scaffold insets the snackbar above the bottom bar
        bottomBar = {
            TbBottomBar(selected = tab, onSelect = { tabState.value = it }, unreadCount = unread)
        },
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tab",
            ) { target ->
                when (target) {
                    TbTab.HOME -> HomeScreen(
                        vm = discoveryVm,
                        nav = nav,
                        snack = snack,
                        openShare = onShare,
                    )
                    TbTab.SEARCH -> SearchScreen(
                        vm = discoveryVm,
                        nav = nav,
                        snack = snack,
                        openShare = onShare,
                    )
                    TbTab.SAVED -> SavedScreen(
                        vm = savedVm,
                        nav = nav,
                        openShare = onShare,
                    )
                    TbTab.ALERTS -> NotificationsScreen(vm = alertsVm, nav = nav)
                    TbTab.MORE -> MoreScreen(nav = nav)
                }
            }
        }
    }

    whatsNew?.let { notes ->
        AlertDialog(
            onDismissRequest = { whatsNew = null },
            title = { Text(stringResource(R.string.whats_new_title, notes.version)) },
            text = {
                Column {
                    notes.highlights.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { whatsNew = null }) {
                    Text(stringResource(R.string.whats_new_got_it))
                }
            },
        )
    }
}
