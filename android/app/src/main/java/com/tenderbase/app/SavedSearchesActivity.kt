package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.tenderbase.app.databinding.ActivitySavedSearchesBinding
import kotlinx.coroutines.launch

/**
 * Saved searches: the user's persisted discovery queries with per-search
 * alert toggles. Tapping a search re-opens discovery with those filters.
 */
class SavedSearchesActivity : AppCompatActivity() {

    private lateinit var b: ActivitySavedSearchesBinding
    private lateinit var repo: TenderRepository
    private val adapter = SavedSearchAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySavedSearchesBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.retryButton.setOnClickListener { load() }
        load()
    }

    private fun load() {
        b.errorView.visibility = View.GONE
        b.emptyView.visibility = View.GONE
        b.titleCount.text = getString(R.string.loading)
        lifecycleScope.launch {
            try {
                val searches = ApiClient.fetchSavedSearches(repo.clientId())
                adapter.submit(searches)
                b.titleCount.text = resources.getQuantityString(
                    R.plurals.saved_searches_count, searches.size, searches.size
                )
                if (searches.isEmpty()) b.emptyView.visibility = View.VISIBLE
            } catch (_: Exception) {
                adapter.submit(emptyList())
                b.titleCount.text = ""
                b.errorView.visibility = View.VISIBLE
            }
        }
    }

    private fun applySearch(info: ApiClient.SavedSearchInfo) {
        val filters = SearchFilters.fromSavedSearchPayload(info.payload)
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_APPLY_FILTERS, filters.toJson())
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(i)
        finish()
    }

    private fun toggleAlerts(info: ApiClient.SavedSearchInfo, enabled: Boolean) {
        lifecycleScope.launch {
            try {
                ApiClient.setSavedSearchAlerts(repo.clientId(), info.id, enabled)
            } catch (_: Exception) {
                Toast.makeText(this@SavedSearchesActivity, R.string.saved_search_delete_failed,
                    Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    private fun deleteSearch(info: ApiClient.SavedSearchInfo) {
        lifecycleScope.launch {
            try {
                ApiClient.deleteSavedSearch(repo.clientId(), info.id)
                Toast.makeText(this@SavedSearchesActivity, R.string.saved_search_deleted,
                    Toast.LENGTH_SHORT).show()
                load()
            } catch (_: Exception) {
                Toast.makeText(this@SavedSearchesActivity, R.string.saved_search_delete_failed,
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class SavedSearchAdapter :
        RecyclerView.Adapter<SavedSearchAdapter.VH>() {

        private val items = mutableListOf<ApiClient.SavedSearchInfo>()

        fun submit(newItems: List<ApiClient.SavedSearchInfo>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_saved_search, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val info = items[position]
            holder.name.text = info.name
            holder.summary.text =
                SearchFilters.fromSavedSearchPayload(info.payload).summary()
            holder.alerts.setOnCheckedChangeListener(null)
            holder.alerts.isChecked = info.alertsEnabled
            holder.alerts.setOnCheckedChangeListener { _, checked ->
                toggleAlerts(info, checked)
            }
            holder.delete.setOnClickListener { deleteSearch(info) }
            holder.card.setOnClickListener { applySearch(info) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.searchCard)
            val name: TextView = v.findViewById(R.id.searchName)
            val summary: TextView = v.findViewById(R.id.searchSummary)
            val alerts: MaterialSwitch = v.findViewById(R.id.alertsSwitch)
            val delete: ImageButton = v.findViewById(R.id.deleteButton)
        }
    }
}
