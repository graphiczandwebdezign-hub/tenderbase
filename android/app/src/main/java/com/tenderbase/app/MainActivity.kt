package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.tenderbase.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: TenderAdapter

    private var currentSearch: String? = null
    private var currentCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

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
        b.search.setOnClickListener { /* focus handled by system */ }

        b.retryButton.setOnClickListener { load() }

        loadCategories()
        load()
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            val cats = try { ApiClient.fetchCategories() } catch (e: Exception) { emptyList() }
            if (cats.isEmpty()) return@launch
            b.categoryChips.removeAllViews()
            // "All" chip
            addFilterChip("All", isAll = true, checked = true)
            for (c in cats) addFilterChip(c, isAll = false, checked = false)
            b.categoryScroll.visibility = View.VISIBLE
        }
    }

    private fun addFilterChip(label: String, isAll: Boolean, checked: Boolean) {
        val chip = Chip(this)
        chip.text = label
        chip.isCheckable = true
        chip.isChecked = checked
        chip.setOnClickListener {
            // Single-select behaviour.
            for (i in 0 until b.categoryChips.childCount) {
                (b.categoryChips.getChildAt(i) as? Chip)?.isChecked = false
            }
            chip.isChecked = true
            currentCategory = if (isAll) null else label
            load()
        }
        b.categoryChips.addView(chip)
    }

    private fun load() {
        showLoading()
        lifecycleScope.launch {
            try {
                val page = ApiClient.fetchTenders(
                    page = 1, limit = 50,
                    search = currentSearch,
                    category = currentCategory
                )
                adapter.submit(page.items)
                if (page.items.isEmpty()) {
                    showEmpty()
                } else {
                    showList()
                    b.subtitle.text = resources.getQuantityString(
                        R.plurals.tenders_count, page.total, page.total
                    )
                }
            } catch (e: Exception) {
                showError(e.message ?: "Something went wrong")
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
