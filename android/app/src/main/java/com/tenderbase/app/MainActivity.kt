package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
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

        b.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentSearch = b.search.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
                load()
                true
            } else false
        }

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
        showLoading()
        b.offlineBanner.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val provincesFilter = if (filterMyCategories) {
                    // or filter by user provinces if desired
                    null
                } else null

                val categoriesFilter = if (filterMyCategories) {
                    repo.getSelectedCategories().firstOrNull()
                } else currentCategory

                val page = ApiClient.fetchTenders(
                    page = 1, limit = 100,
                    search = currentSearch,
                    category = categoriesFilter,
                    province = provincesFilter
                )

                var items = page.items
                if (filterClosingSoon) {
                    items = items.filter { DateUtils.isUrgent(it.closingAt, it.closingDate, 7) }
                        .sortedBy { it.closingAt ?: it.closingDate ?: "" }
                }

                // Cache tenders for offline use
                repo.cacheTenders(items)

                adapter.submit(items)
                if (items.isEmpty()) {
                    showEmpty()
                } else {
                    showList()
                    b.subtitle.text = resources.getQuantityString(
                        R.plurals.tenders_count, items.size, items.size
                    )
                }
            } catch (e: Exception) {
                // Fallback to offline cache
                val cached = repo.getCachedTenders()
                if (cached.isNotEmpty()) {
                    var items = cached
                    if (filterClosingSoon) {
                        items = items.filter { DateUtils.isUrgent(it.closingAt, it.closingDate, 7) }
                    }
                    adapter.submit(items)
                    showList()
                    b.offlineBanner.visibility = View.VISIBLE
                    b.subtitle.text = "${items.size} tenders (offline cache)"
                } else {
                    showError(e.message ?: "Connection failed")
                }
            } finally {
                b.swipe.isRefreshing = false
            }
        }
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
