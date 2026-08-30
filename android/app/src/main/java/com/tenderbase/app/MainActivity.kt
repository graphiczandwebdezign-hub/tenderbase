package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: TenderAdapter
    private lateinit var repo: TenderRepository
    private lateinit var toggle: ActionBarDrawerToggle

    private var currentSearch: String? = null
    private var currentCategory: String? = null
    private var filterClosingSoon: Boolean = false
    private var filterMyCategories: Boolean = false

    private var currentPage: Int = 1
    private var hasMore: Boolean = false
    private var isLoadingMore: Boolean = false
    private var isRefreshing: Boolean = false

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

        b.navigationView.setNavigationItemSelectedListener { menuItem ->
            b.drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_latest -> {
                    currentCategory = null
                    filterClosingSoon = false
                    filterMyCategories = false
                    load()
                }
                R.id.nav_notifications -> startActivity(Intent(this, NotificationsActivity::class.java))
                R.id.nav_saved -> startActivity(Intent(this, SavedTendersActivity::class.java))
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

        adapter = TenderAdapter(emptyList()) { tender ->
            val i = Intent(this, DetailActivity::class.java)
            i.putExtra(DetailActivity.EXTRA_ID, tender.id)
            startActivity(i)
        }
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.swipe.setColorSchemeResources(R.color.primary)
        b.swipe.setOnRefreshListener { load() }

        b.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (hasMore && !isLoadingMore && !isRefreshing &&
                    lastVisible >= lm.itemCount - 4) {
                    loadMore()
                }
            }
        })

        b.retryButton.setOnClickListener { load() }

        setupQuickFilters()
        loadCategories()
        load()

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
                showSearchDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setText(currentSearch.orEmpty())
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(input)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.search_dialog_title))
            .setView(container)
            .setPositiveButton(getString(R.string.search_action_search)) { _, _ ->
                applySearch(input.text?.toString()?.trim())
            }
            .setNegativeButton(
                if (currentSearch == null) getString(R.string.cancel)
                else getString(R.string.search_action_clear)
            ) { d, _ ->
                if (currentSearch != null) {
                    currentSearch = null
                    load()
                }
                d.dismiss()
            }
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applySearch(input.text?.toString()?.trim())
                dialog.dismiss()
                true
            } else false
        }

        dialog.show()
    }

    private fun applySearch(query: String?) {
        currentSearch = query?.takeIf { it.isNotEmpty() }
        load()
    }

    private fun setupQuickFilters() {
        b.categoryChips.removeAllViews()
        addFilterChip("All", isAll = true, checked = true) {
            currentCategory = null
            filterClosingSoon = false
            filterMyCategories = false
            load()
        }
        addFilterChip("My Categories", isAll = false, checked = false) {
            currentCategory = null
            filterClosingSoon = false
            filterMyCategories = true
            load()
        }
        addFilterChip("Closing Soon", isAll = false, checked = false) {
            currentCategory = null
            filterClosingSoon = true
            filterMyCategories = false
            load()
        }
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            val cats = try { ApiClient.fetchCategories() } catch (e: Exception) { emptyList() }
            if (cats.isEmpty()) return@launch
            for (c in cats) {
                addFilterChip(c, isAll = false, checked = false) {
                    currentCategory = c
                    filterClosingSoon = false
                    filterMyCategories = false
                    load()
                }
            }
        }
    }

    private fun addFilterChip(label: String, isAll: Boolean, checked: Boolean, onClick: () -> Unit) {
        val chip = Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnClickListener {
                for (i in 0 until b.categoryChips.childCount) {
                    (b.categoryChips.getChildAt(i) as? Chip)?.isChecked = false
                }
                this.isChecked = true
                onClick()
            }
        }
        b.categoryChips.addView(chip)
    }

    private fun load() {
        if (isRefreshing) return
        isRefreshing = true
        currentPage = 1
        hasMore = false
        isLoadingMore = false
        adapter.hideFooter()
        showLoading()
        b.offlineBanner.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val page = fetchPage(1)
                val items = filterItems(page.items)
                currentPage = page.page
                hasMore = currentPage < page.totalPages

                // Cache tenders for offline use
                repo.cacheTenders(items)

                adapter.submit(items)
                if (items.isEmpty()) {
                    showEmpty()
                } else {
                    showList()
                    updateSubtitle(page.total, items.size)
                }
            } catch (e: Exception) {
                // Fallback to offline cache
                val cached = repo.getCachedTenders()
                if (cached.isNotEmpty()) {
                    val items = filterItems(cached)
                    adapter.submit(items)
                    showList()
                    b.offlineBanner.visibility = View.VISIBLE
                    b.subtitle.text = "${items.size} tenders (offline cache)"
                } else {
                    showError(e.message ?: "Connection failed")
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
        adapter.showFooter()
        val nextPage = currentPage + 1
        lifecycleScope.launch {
            try {
                val page = fetchPage(nextPage)
                val more = filterItems(page.items)
                adapter.append(more)
                currentPage = page.page
                hasMore = currentPage < page.totalPages
                repo.cacheTenders(more)
            } catch (_: Exception) {
                // Network hiccup — keep hasMore so scrolling again will retry.
            } finally {
                isLoadingMore = false
                adapter.hideFooter()
            }
        }
    }

    private suspend fun fetchPage(page: Int): ApiClient.Page = ApiClient.fetchTenders(
        page = page, limit = 100,
        search = currentSearch,
        category = categoriesFilter(),
        province = null
    )

    private fun categoriesFilter(): String? =
        if (filterMyCategories) repo.getSelectedCategories().firstOrNull() else currentCategory

    private fun filterItems(items: List<Tender>): List<Tender> {
        if (!filterClosingSoon) return items
        return items.filter { DateUtils.isUrgent(it.closingAt, it.closingDate, 7) }
            .sortedBy { it.closingAt ?: it.closingDate ?: "" }
    }

    private fun updateSubtitle(total: Int, shown: Int) {
        val count = if (filterClosingSoon) shown else total
        b.subtitle.text = resources.getQuantityString(
            R.plurals.tenders_count, count, count
        )
    }

    private fun showLoading() {
        if (!b.swipe.isRefreshing) b.progress.visibility = View.VISIBLE
        b.errorView.visibility = View.GONE
        b.emptyView.visibility = View.GONE
    }

    private fun showList() {
        b.progress.visibility = View.GONE
        b.errorView.visibility = View.GONE
        b.emptyView.visibility = View.GONE
        b.recycler.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        b.progress.visibility = View.GONE
        b.errorView.visibility = View.GONE
        b.recycler.visibility = View.GONE
        b.emptyView.visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        b.progress.visibility = View.GONE
        b.recycler.visibility = View.GONE
        b.emptyView.visibility = View.GONE
        b.errorText.text = getString(R.string.error_body, msg)
        b.errorView.visibility = View.VISIBLE
    }
}
