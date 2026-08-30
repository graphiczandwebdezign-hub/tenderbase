package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tenderbase.app.databinding.ActivitySavedTendersBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SavedTendersActivity : AppCompatActivity() {

    private lateinit var b: ActivitySavedTendersBinding
    private lateinit var repo: TenderRepository
    private lateinit var adapter: TenderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySavedTendersBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)
        adapter = TenderAdapter(
            onTenderClick = { tender ->
                val i = Intent(this, DetailActivity::class.java)
                i.putExtra(DetailActivity.EXTRA_ID, tender.id)
                startActivity(i)
            },
            onSaveToggle = { tender ->
                // Un-save straight from the list; the Room flow refreshes it.
                lifecycleScope.launch { repo.toggleSave(tender) }
            },
            onRetry = { }
        )

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        lifecycleScope.launch {
            repo.savedTendersFlow.collectLatest { savedList ->
                val tenders = savedList.map { it.toTender() }
                adapter.submitTenders(tenders, null)
                if (tenders.isEmpty()) {
                    b.recycler.visibility = View.GONE
                    b.emptyView.visibility = View.VISIBLE
                    b.titleCount.text = "No saved tenders"
                } else {
                    b.recycler.visibility = View.VISIBLE
                    b.emptyView.visibility = View.GONE
                    b.titleCount.text = resources.getQuantityString(
                        R.plurals.tenders_count, tenders.size, tenders.size
                    )
                }
            }
        }
    }
}
