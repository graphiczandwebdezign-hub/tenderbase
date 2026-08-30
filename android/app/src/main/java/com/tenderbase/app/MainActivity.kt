package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tenderbase.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tender discovery screen: search → filter → sort → find.
 *
 * All list state lives in [filters] ([SearchFilters]); every reload is a
 * single server-side query (search + filters + sort + pagination), so state
 * composes predictably and survives detail-screen navigation and rotation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: TenderAdapter
    private lateinit var repo: TenderRepository
    private lateinit var toggle: ActionBarDrawerToggle

    /** The single source of truth for the discovery query. */
    private var filters = SearchFilters()

    private var currentPage = 1
    private var totalCount = 0
    private var hasMore = false
    private var isLoadingMore = false
    private var isRefreshing = false
    private var facetsJson: String? = null

    private var searchJob: Job? = null

    companion object {
        private const val STATE_FILTERS = "discovery_filters"
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val PAGE_SIZE = 20
        private const val PREFS = "tenderbase_prefs"
        private const val PREF_SORT = "last_sort"

        /** Intent extra: a saved-search filters JSON to apply on open. */
        const val EXTRA_APPLY_FILTERS = "apply_filters_json"
        /** Intent extra: a named discovery preset ("closing_week"). */
        const val EXTRA_PRESET = "discovery_preset"
        const val PRESET_CLOSING_WEEK = "closing_week"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        repo = TenderRepository(this)

        toggle = ActionBarDrawerToggle(
            this, b.drawerLayout, b.toolbar,
            R.string.app_name, R.string.app_name
        )
        b.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Restore discovery state after rotation (search + filters + sort).
        val restored = savedInstanceState?.getString(STATE_FILTERS)
        if (restored != null) {
            filters = SearchFilters.fromJson(restored)
        } else if (intent?.hasExtra(EXTRA_APPLY_FILTERS) == true) {
            // Opened by tapping a saved search.
            filters = SearchFilters.fromJson(intent.getStringExtra(EXTRA_APPLY_FILTERS))
        } else if (intent?.hasExtra(EXTRA_PRESET) == true) {
            filters = presetFilters(intent.getStringExtra(EXTRA_PRESET))
        } else {
            filters = filters.copy(
                sort = SortOption.fromKey(
                    getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SORT, null)
                )
            )
        }

        setupNavigation()
        setupList()
        setupSearch()
        setupControls()

        // Filter sheet results come back through the fragment result API.
        supportFragmentManager.setFragmentResultListener(
            FilterBottomSheet.REQUEST_KEY, this
        ) { _, bundle ->
            val json = bundle.getString(FilterBottomSheet.RESULT_FILTERS) ?: return@setFragmentResultListener
            val applied = SearchFilters.fromJson(json)
            filters = applied.copy(query = appliedQuery(), sort = filters.sort)
            load()
        }

        // Saved tender ids drive the card star state.
        lifecycleScope.launch {
            repo.savedTendersFlow.collectLatest { saved ->
                adapter.setSavedIds(saved.map { it.id }.toSet())
            }
        }

        // Observe unread notifications count for badge
        lifecycleScope.launch {
            repo.unreadCountFlow.collectLatest { count ->
                val menu = b.navigationView.menu
                val notifItem = menu.findItem(R.id.nav_notifications)
                if (notifItem != null) {
                    notifItem.title = if (count > 0) "Notifications ($count)" else "Notifications"
                }
            }
        }

        b.searchInput.setText(filters.query)
        updateSortButtonLabel()
        load()
        loadFacets()
    }

    // ------------------------------------------------------------- setup

    /** Map a named preset onto discovery state (deadline intelligence). */
    private fun presetFilters(preset: String?): SearchFilters = when (preset) {
        PRESET_CLOSING_WEEK -> SearchFilters.closingWeekPreset(sort = filters.sort)
        else -> SearchFilters()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Launched from SavedSearchesActivity / notifications while alive.
        val applyJson = intent.getStringExtra(EXTRA_APPLY_FILTERS)
        val preset = intent.getStringExtra(EXTRA_PRESET)
        if (applyJson != null) {
            filters = SearchFilters.fromJson(applyJson)
            b.searchInput.setText(filters.query)
            updateSortButtonLabel()
            load()
            renderFilterChips()
        } else if (preset != null) {
            filters = presetFilters(preset)
            b.searchInput.setText("")
            updateSortButtonLabel()
            load()
            renderFilterChips()
        }
    }

    private fun setupNavigation() {
        b.navigationView.setNavigationItemSelectedListener { menuItem ->
            b.drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_latest -> {
                    filters = SearchFilters(sort = filters.sort)
                    b.searchInput.setText("")
                    load()
                }
                R.id.nav_closing_week -> {
                    filters = SearchFilters.closingWeekPreset(sort = filters.sort)
                    b.searchInput.setText("")
                    updateSortButtonLabel()
                    renderFilterChips()
                    load()
                }
                R.id.nav_notifications -> startActivity(Intent(this, NotificationsActivity::class.java))
                R.id.nav_saved -> startActivity(Intent(this, SavedTendersActivity::class.java))
                R.id.nav_saved_searches -> startActivity(Intent(this, SavedSearchesActivity::class.java))
                R.id.nav_downloads -> startActivity(Intent(this, DownloadsActivity::class.java))
                R.id.nav_categories -> {
                    val i = Intent(this, PreferencesActivity::class.java)
                    i.putExtra(PreferencesActivity.EXTRA_TYPE, PreferencesActivity.TYPE_CATEGORIES)
                    startActivity(i)
                }
                R.id.nav_provinces -> {
                    val i = Intent(this, PreferencesActivity::class.java)
                    i.putExtra(PreferencesActivity.EXTRA_TYPE, PreferencesActivity.TYPE_PROVINCES)
                    startActivity(i)
                }
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
    }

    private fun setupList() {
        adapter = TenderAdapter(
            onTenderClick = { tender ->
                val i = Intent(this, DetailActivity::class.java)
                i.putExtra(DetailActivity.EXTRA_ID, tender.id)
                startActivity(i)
            },
            onSaveToggle = { tender ->
                lifecycleScope.launch { repo.toggleSave(tender) }
            },
            onRetry = { loadMore() }
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.swipe.setColorSchemeResources(R.color.primary)
        b.swipe.setOnRefreshListener {
            // Pull-to-refresh keeps current state; only data reloads.
            load()
            // Refresh facet counts opportunistically on manual refresh only.
            loadFacets()
        }

        b.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (hasMore && !isLoadingMore && !isRefreshing &&
                    lastVisible >= lm.itemCount - 4
                ) {
                    loadMore()
                }
            }
        })

        b.retryButton.setOnClickListener { load() }
        b.clearFiltersButton.setOnClickListener {
            filters = filters.copy(
                provinces = emptyList(), categories = emptyList(), sources = emptyList(),
                status = null, dateFilter = DateFilter.ANY,
                closingAfter = null, closingBefore = null
            )
            load()
        }
        b.resetSearchButton.setOnClickListener {
            filters = filters.copy(query = "")
            b.searchInput.setText("")
            load()
        }
    }

    private fun setupSearch() {
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                applyQueryNow()
                hideKeyboard()
                true
            } else false
        }
        b.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                b.clearSearch.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                // Debounce: fire only once typing pauses.
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    if (appliedQuery() != text.trim()) applyQueryNow()
                }
            }
        })
        b.clearSearch.setOnClickListener {
            b.searchInput.setText("")
            searchJob?.cancel()
            applyQueryNow()
            b.searchInput.requestFocus()
        }
    }

    private fun setupControls() {
        b.filterButton.setOnClickListener {
            FilterBottomSheet.create(filters, facetsJson)
                .show(supportFragmentManager, FilterBottomSheet::class.java.simpleName)
        }
        b.sortButton.setOnClickListener { showSortDialog() }
        b.saveSearchButton.setOnClickListener { showSaveSearchDialog() }
    }

    /** Save the current discovery state as a server-side alert. */
    private fun showSaveSearchDialog() {
        if (filters.isDefault()) {
            android.widget.Toast.makeText(
                this, R.string.save_search_needs_filters, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.save_search_hint)
            setText(defaultSearchName())
            setSelectAllOnFocus(true)
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.save_search_title))
            .setMessage(filters.summary())
            .setView(container)
            .setPositiveButton(getString(R.string.save_search_action)) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) saveSearch(name)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun defaultSearchName(): String =
        filters.query.takeIf { it.isNotBlank() } ?: filters.summary().take(40)

    private fun saveSearch(name: String) {
        val clientId = repo.clientId()
        val payload = filters.toSavedSearchPayload()
        lifecycleScope.launch {
            try {
                ApiClient.createSavedSearch(clientId, name, payload)
                android.widget.Toast.makeText(
                    this@MainActivity, R.string.save_search_done, android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: ApiClient.ApiException) {
                val msg = if (e.statusCode == 409) R.string.save_search_duplicate
                else R.string.save_search_failed
                android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG)
                    .show()
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    this@MainActivity, R.string.save_search_failed, android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------- actions

    private fun appliedQuery(): String = filters.query.trim()

    /** Commit the typed text as the active search query and reload. */
    private fun applyQueryNow() {
        val text = b.searchInput.text?.toString()?.trim().orEmpty()
        if (filters.query == text) return
        filters = filters.copy(query = text)
        load()
    }

    private fun showSortDialog() {
        val searching = appliedQuery().isNotBlank()
        // Relevance is only offered when the backend has a query to rank.
        val options = mutableListOf(
            SortOption.NEWEST to getString(R.string.sort_newest),
            SortOption.CLOSING to getString(R.string.sort_closing),
            SortOption.UPDATED to getString(R.string.sort_updated)
        )
        if (searching) {
            options.add(SortOption.RELEVANCE to getString(R.string.sort_relevance))
        }
        val labels = options.map { it.second }.toTypedArray()
        val selected = options.indexOfFirst { it.first == filters.sort }.takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sort_title))
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                filters = filters.copy(sort = options[which].first)
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(PREF_SORT, filters.sort.key).apply()
                updateSortButtonLabel()
                dialog.dismiss()
                load()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateSortButtonLabel() {
        // Compact labels on the button; full labels in the dialog.
        b.sortButton.text = when (filters.sort) {
            SortOption.NEWEST -> getString(R.string.sort_short_newest)
            SortOption.CLOSING -> getString(R.string.sort_closing)
            SortOption.UPDATED -> getString(R.string.sort_short_updated)
            SortOption.RELEVANCE -> getString(R.string.sort_relevance)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
    }

    // ------------------------------------------------------------ data flow

    private fun load() {
        if (isRefreshing) return
        isRefreshing = true
        currentPage = 1
        hasMore = false
        isLoadingMore = false
        b.offlineBanner.visibility = View.GONE

        // First page: skeletons unless a list is already on screen (refresh).
        if (adapter.tenderCount == 0) {
            adapter.showSkeletons()
            showList()
        }

        lifecycleScope.launch {
            try {
                val page = ApiClient.fetchTenders(page = 1, limit = PAGE_SIZE, filters = filters)
                totalCount = page.total
                currentPage = page.page
                hasMore = currentPage < page.totalPages
                val footer = when {
                    hasMore -> TenderAdapter.FooterState.LOADING
                    page.items.isEmpty() -> null
                    else -> TenderAdapter.FooterState.END
                }
                adapter.submitTenders(page.items, footer)
                repo.cacheTenders(page.items)

                if (page.items.isEmpty()) showEmpty() else showList()
                updateCountText()
                renderFilterChips()
            } catch (e: Exception) {
                // Offline fallback: cached tenders keep the app usable.
                val cached = repo.getCachedTenders()
                if (cached.isNotEmpty()) {
                    totalCount = cached.size
                    hasMore = false
                    adapter.submitTenders(cached, null)
                    showList()
                    b.offlineBanner.visibility = View.VISIBLE
                    updateCountText(offline = true)
                    renderFilterChips()
                } else {
                    showError()
                }
            } finally {
                isRefreshing = false
                b.swipe.isRefreshing = false
            }
        }
    }

    private fun loadMore() {
        if (isRefreshing || isLoadingMore || !hasMore) return
        isLoadingMore = true
        adapter.submitFooter(TenderAdapter.FooterState.LOADING)
        val nextPage = currentPage + 1
        lifecycleScope.launch {
            try {
                val page = ApiClient.fetchTenders(page = nextPage, limit = PAGE_SIZE, filters = filters)
                totalCount = page.total
                currentPage = page.page
                hasMore = currentPage < page.totalPages
                val more = page.items.filter { !adapter.contains(it.id) }
                val footer = if (hasMore) TenderAdapter.FooterState.LOADING else TenderAdapter.FooterState.END
                adapter.appendTenders(more, footer)
                repo.cacheTenders(more)
                updateCountText()
            } catch (_: Exception) {
                // Keep hasMore so the retry footer can attempt again.
                adapter.submitFooter(TenderAdapter.FooterState.RETRY)
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun loadFacets() {
        lifecycleScope.launch {
            try {
                val facets = ApiClient.fetchFacets()
                facetsJson = FilterBottomSheet.facetsToJson(facets)
            } catch (_: Exception) {
                // Filters still open; option lists just stay empty until online.
            }
        }
    }

    // ------------------------------------------------------------- rendering

    private fun updateCountText(offline: Boolean = false) {
        val total = if (offline) adapter.tenderCount else totalCount
        if (total == 0) {
            b.countText.text = ""
            return
        }
        if (offline) {
            b.countText.text = resources.getQuantityString(R.plurals.tenders_count, total, total)
            return
        }
        val shown = adapter.tenderCount
        b.countText.text = if (shown in 1 until total) {
            getString(R.string.showing_range, 1, shown, total)
        } else {
            resources.getQuantityString(R.plurals.tenders_found, total, total)
        }
    }

    /** Active-filter chips with per-chip removal + Clear all. */
    private fun renderFilterChips() {
        val group = b.filterChips
        group.removeAllViews()
        val active = mutableListOf<Pair<String, () -> Unit>>()

        filters.provinces.forEach { p ->
            active.add(p to { filters = filters.copy(provinces = filters.provinces - p) })
        }
        filters.categories.forEach { c ->
            active.add(c to { filters = filters.copy(categories = filters.categories - c) })
        }
        filters.sources.forEach { s ->
            active.add(s to { filters = filters.copy(sources = filters.sources - s) })
        }
        filters.status?.let { st ->
            val label = when (st) {
                StatusFilter.OPEN -> getString(R.string.status_open)
                StatusFilter.CLOSING_SOON -> getString(R.string.status_closing_soon)
                StatusFilter.CLOSED -> getString(R.string.status_closed)
            }
            active.add(label to { filters = filters.copy(status = null) })
        }
        if (filters.dateFilter != DateFilter.ANY) {
            val label = dateFilterLabel(filters.dateFilter, filters.closingAfter, filters.closingBefore)
            active.add(label to {
                filters = filters.copy(
                    dateFilter = DateFilter.ANY, closingAfter = null, closingBefore = null
                )
            })
        }

        if (active.isEmpty()) {
            b.chipsScroll.visibility = View.GONE
            updateFilterBadge()
            return
        }

        active.forEach { (label, remove) ->
            val chip = Chip(this).apply {
                text = label
                isCloseIconVisible = true
                closeIconContentDescription = getString(R.string.cd_chip_remove, label)
                setOnCloseIconClickListener {
                    remove()
                    load()
                }
            }
            group.addView(chip)
        }
        // Clear all
        val clear = Chip(this).apply {
            text = getString(R.string.clear_all)
            isCloseIconVisible = false
            setOnClickListener {
                filters = filters.copy(
                    provinces = emptyList(), categories = emptyList(), sources = emptyList(),
                    status = null, dateFilter = DateFilter.ANY,
                    closingAfter = null, closingBefore = null
                )
                load()
            }
        }
        group.addView(clear)
        b.chipsScroll.visibility = View.VISIBLE
        updateFilterBadge()
    }

    private fun dateFilterLabel(d: DateFilter, start: String?, end: String?): String =
        when (d) {
            DateFilter.PUBLISHED_TODAY -> getString(R.string.published_today)
            DateFilter.PUBLISHED_7D -> getString(R.string.published_7d)
            DateFilter.PUBLISHED_30D -> getString(R.string.published_30d)
            DateFilter.CLOSING_7D -> getString(R.string.closing_7d)
            DateFilter.CLOSING_14D -> getString(R.string.closing_14d)
            DateFilter.CLOSING_30D -> getString(R.string.closing_30d)
            DateFilter.CLOSING_CUSTOM ->
                if (start != null || end != null)
                    listOfNotNull(start, end).joinToString(" – ")
                else getString(R.string.closing_custom)
            DateFilter.ANY -> ""
        }

    private fun updateFilterBadge() {
        val n = filters.activeFilterCount()
        b.filterButton.text =
            if (n > 0) "${getString(R.string.filter_button)} ($n)" else getString(R.string.filter_button)
    }

    // ----------------------------------------------------------- view states

    private fun showList() {
        b.recycler.visibility = View.VISIBLE
        b.emptyView.visibility = View.GONE
        b.errorView.visibility = View.GONE
    }

    private fun showEmpty() {
        b.recycler.visibility = View.GONE
        b.errorView.visibility = View.GONE
        b.emptyView.visibility = View.VISIBLE
        val filtered = filters.hasActiveFilters() || appliedQuery().isNotBlank()
        b.emptyTitle.setText(
            if (filtered) R.string.no_results_title else R.string.no_tenders_title
        )
        b.emptyBody.setText(
            if (filtered) R.string.no_results_body else R.string.no_tenders_body
        )
        b.clearFiltersButton.visibility = if (filters.hasActiveFilters()) View.VISIBLE else View.GONE
        b.resetSearchButton.visibility = if (appliedQuery().isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun showError() {
        b.recycler.visibility = View.GONE
        b.emptyView.visibility = View.GONE
        b.errorView.visibility = View.VISIBLE
        b.errorText.setText(R.string.error_body_generic)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTERS, filters.toJson())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_search)?.icon?.setTint(
            ContextCompat.getColor(this, R.color.onPrimary)
        )
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                b.searchInput.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (b.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            b.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
