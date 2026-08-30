package com.tenderbase.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.tenderbase.app.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    companion object { const val EXTRA_ID = "tender_id" }

    private lateinit var b: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) { finish(); return }
        load(id)
    }

    private fun load(id: Int) {
        b.progress.visibility = View.VISIBLE
        b.content.visibility = View.GONE
        b.errorView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val t = ApiClient.fetchTender(id)
                bind(t)
                b.content.visibility = View.VISIBLE
            } catch (e: Exception) {
                b.errorText.text = getString(R.string.error_body, e.message ?: "error")
                b.errorView.visibility = View.VISIBLE
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun bind(t: Tender) {
        b.title.text = t.title
        b.org.text = t.organisation ?: "Unknown organisation"
        b.province.text = t.province ?: "Province not specified"
        b.closes.text = DateUtils.closesLabel(t.closingAt, t.closingDate)
        b.closes.setTextColor(
            getColor(if (DateUtils.isUrgent(t.closingAt, t.closingDate)) R.color.urgent else R.color.primary)
        )
        b.closingDate.text = getString(
            R.string.closing_on, DateUtils.prettyDate(t.closingAt, t.closingDate)
        )
        b.tenderType.text = t.tenderType ?: "—"
        b.status.text = t.status ?: "—"

        b.description.text = t.description ?: "No description provided."

        // Category chips
        b.chipGroup.removeAllViews()
        val cats = if (t.categories.isNotEmpty()) t.categories else listOfNotNull(t.category)
        for (c in cats) {
            val chip = Chip(this)
            chip.text = c.replace('-', ' ').replaceFirstChar { it.uppercase() }
            chip.isClickable = false
            chip.isCheckable = false
            b.chipGroup.addView(chip)
        }
        b.chipGroup.visibility = if (b.chipGroup.childCount == 0) View.GONE else View.VISIBLE

        // Documents
        b.docsContainer.removeAllViews()
        if (t.documents.isEmpty()) {
            b.docsLabel.visibility = View.GONE
            b.noDocs.visibility = View.VISIBLE
        } else {
            b.docsLabel.visibility = View.VISIBLE
            b.noDocs.visibility = View.GONE
            for (d in t.documents) {
                val tv = layoutInflater.inflate(R.layout.item_document, b.docsContainer, false) as TextView
                tv.text = d.title
                tv.setOnClickListener { openUrl(d.url) }
                b.docsContainer.addView(tv)
            }
        }

        // Open on eTenders
        if (!t.sourceUrl.isNullOrBlank()) {
            b.openSource.visibility = View.VISIBLE
            b.openSource.setOnClickListener { openUrl(t.sourceUrl) }
        } else {
            b.openSource.visibility = View.GONE
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { /* no browser */ }
    }
}
