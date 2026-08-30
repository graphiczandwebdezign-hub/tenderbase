package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tenderbase.app.databinding.ActivityDeadlinesBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deadline command centre: everything that needs action, in one place.
 *
 * Sections (single scrolling list):
 *  1. Closing this week — every open tender closing within 7 days (server).
 *  2. Saved deadlines — the user's saved tenders grouped by urgency (local).
 *  3. Recent alerts — the last saved-search/deadline notifications (local),
 *     with a shortcut to the full history.
 */
class DeadlinesActivity : AppCompatActivity() {

    private lateinit var b: ActivityDeadlinesBinding
    private lateinit var repo: TenderRepository
    private val adapter = DashboardAdapter()

    private var savedTenders: List<Tender> = emptyList()
    private var alerts: List<NotificationEntity> = emptyList()
    private var closingSoon: List<Tender> = emptyList()
    private var closingSoonFailed = false
    private var savedIds: Set<Int> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDeadlinesBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.swipe.setColorSchemeResources(R.color.primary)
        b.swipe.setOnRefreshListener { loadClosingSoon() }
        b.retryButton.setOnClickListener { loadClosingSoon() }

        // Local sections update live.
        lifecycleScope.launch {
            repo.savedTendersFlow.collectLatest { saved ->
                savedTenders = saved.map { it.toTender() }
                savedIds = saved.map { it.id }.toSet()
                render()
            }
        }
        lifecycleScope.launch {
            repo.notificationHistoryFlow.collectLatest { list ->
                alerts = list
                render()
            }
        }

        loadClosingSoon()
    }

    private fun loadClosingSoon() {
        closingSoonFailed = false
        lifecycleScope.launch {
            try {
                val page = ApiClient.fetchTenders(
                    page = 1, limit = 20, filters = Dashboard.closingThisWeekFilters()
                )
                closingSoon = Dashboard.filterHidden(page.items, repo.hiddenTenderIds())
            } catch (_: Exception) {
                closingSoon = emptyList()
                closingSoonFailed = true
            } finally {
                b.swipe.isRefreshing = false
                render()
            }
        }
    }

    private fun render() {
        val hasAnything = closingSoon.isNotEmpty() || savedTenders.isNotEmpty() || alerts.isNotEmpty()
        b.progress.visibility =
            if (hasAnything || closingSoonFailed) View.GONE else View.VISIBLE
        b.errorView.visibility = if (closingSoonFailed && !hasAnything) View.VISIBLE else View.GONE
        b.recycler.visibility = if (hasAnything) View.VISIBLE else View.GONE

        adapter.submit(buildRows())
        b.subtitle.text = when {
            closingSoonFailed -> getString(R.string.deadlines_partial_error)
            else -> getString(
                R.string.deadlines_subtitle,
                closingSoon.size,
                savedTenders.count { DateUtils.urgency(it.closingAt, it.closingDate, it.deadlineState) != DateUtils.Urgency.CLOSED }
            )
        }
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()

        if (closingSoon.isNotEmpty()) {
            rows += Row.Header(getString(R.string.section_closing_week, closingSoon.size))
            closingSoon.take(10).forEach { rows += Row.TenderRow(it) }
        }

        if (savedTenders.isNotEmpty()) {
            for ((bucket, items) in Dashboard.groupByDeadline(savedTenders)) {
                rows += Row.Header(bucketLabel(bucket, items.size))
                items.forEach { rows += Row.TenderRow(it) }
            }
        }

        if (alerts.isNotEmpty()) {
            rows += Row.Header(getString(R.string.section_alerts, alerts.size))
            alerts.take(3).forEach { rows += Row.AlertRow(it) }
            if (alerts.size > 3) rows += Row.ViewAll
        }

        if (rows.isEmpty() && !closingSoonFailed) {
            rows += Row.Header(getString(R.string.deadlines_empty))
        }
        return rows
    }

    private fun bucketLabel(bucket: Dashboard.Bucket, count: Int): String = when (bucket) {
        Dashboard.Bucket.CLOSED -> getString(R.string.bucket_closed, count)
        Dashboard.Bucket.TODAY -> getString(R.string.bucket_today, count)
        Dashboard.Bucket.THIS_WEEK -> getString(R.string.bucket_this_week, count)
        Dashboard.Bucket.TWO_WEEKS -> getString(R.string.bucket_two_weeks, count)
        Dashboard.Bucket.LATER -> getString(R.string.bucket_later, count)
        Dashboard.Bucket.NO_DATE -> getString(R.string.bucket_no_date, count)
    }

    private fun openTender(id: Int) {
        startActivity(
            Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, id)
        )
    }

    // --------------------------------------------------------------- adapter

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class TenderRow(val tender: Tender) : Row()
        data class AlertRow(val alert: NotificationEntity) : Row()
        object ViewAll : Row()
    }

    private inner class DashboardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<Row>()

        fun submit(rows: List<Row>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.TenderRow -> TYPE_TENDER
            is Row.AlertRow -> TYPE_ALERT
            is Row.ViewAll -> TYPE_VIEW_ALL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(
                    inflater.inflate(R.layout.item_section_header, parent, false)
                )
                TYPE_ALERT -> AlertVH(
                    inflater.inflate(R.layout.item_notification, parent, false)
                )
                TYPE_VIEW_ALL -> ViewAllVH(
                    inflater.inflate(R.layout.item_view_all, parent, false)
                )
                else -> TenderVH(
                    inflater.inflate(R.layout.item_tender, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header ->
                    (holder as HeaderVH).title.text = row.title
                is Row.TenderRow -> (holder as TenderVH).bind(row.tender)
                is Row.AlertRow -> (holder as AlertVH).bind(row.alert)
                is Row.ViewAll -> (holder as ViewAllVH).bind()
            }
        }

        inner class TenderVH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(t: Tender) {
                TenderCardBinder.bind(
                    view = itemView,
                    t = t,
                    isSaved = t.id in savedIds,
                    onClick = { openTender(t.id) },
                    onSaveToggle = { tender ->
                        lifecycleScope.launch { repo.toggleSave(tender) }
                    }
                )
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.sectionTitle)
        }

        inner class AlertVH(v: View) : RecyclerView.ViewHolder(v) {
            private val title: TextView = v.findViewById(R.id.notifTitle)
            private val body: TextView = v.findViewById(R.id.notifBody)
            private val time: TextView = v.findViewById(R.id.notifTime)
            private val dot: View = v.findViewById(R.id.notifDot)

            fun bind(alert: NotificationEntity) {
                title.text = alert.title
                body.text = alert.body
                time.text = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
                    .format(Date(alert.timestamp))
                dot.visibility = if (alert.isRead) View.INVISIBLE else View.VISIBLE
                itemView.setOnClickListener {
                    lifecycleScope.launch { repo.markNotificationRead(alert.id) }
                    if (alert.tenderId > 0) openTender(alert.tenderId)
                }
            }
        }

        inner class ViewAllVH(v: View) : RecyclerView.ViewHolder(v) {
            private val text: TextView = v.findViewById(R.id.viewAllText)

            fun bind() {
                text.text = getString(R.string.view_all_alerts)
                itemView.setOnClickListener {
                    startActivity(Intent(this@DeadlinesActivity, NotificationsActivity::class.java))
                }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TENDER = 1
        const val TYPE_ALERT = 2
        const val TYPE_VIEW_ALL = 3
    }
}
