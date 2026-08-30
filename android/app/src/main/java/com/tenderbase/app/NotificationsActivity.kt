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
import com.tenderbase.app.databinding.ActivityNotificationsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsActivity : AppCompatActivity() {

    private lateinit var b: ActivityNotificationsBinding
    private lateinit var repo: TenderRepository
    private lateinit var adapter: NotificationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)
        adapter = NotificationAdapter { notification ->
            lifecycleScope.launch {
                repo.markNotificationRead(notification.id)
            }
            if (notification.tenderId > 0) {
                val i = Intent(this, DetailActivity::class.java)
                i.putExtra(DetailActivity.EXTRA_ID, notification.tenderId)
                startActivity(i)
            }
        }

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.markAllRead.setOnClickListener {
            lifecycleScope.launch {
                repo.markAllNotificationsRead()
            }
        }

        lifecycleScope.launch {
            repo.notificationHistoryFlow.collectLatest { list ->
                adapter.submit(list)
                if (list.isEmpty()) {
                    b.recycler.visibility = View.GONE
                    b.emptyView.visibility = View.VISIBLE
                    b.markAllRead.visibility = View.GONE
                } else {
                    b.recycler.visibility = View.VISIBLE
                    b.emptyView.visibility = View.GONE
                    b.markAllRead.visibility = View.VISIBLE
                }
            }
        }
    }

    class NotificationAdapter(
        private val onClick: (NotificationEntity) -> Unit
    ) : RecyclerView.Adapter<NotificationAdapter.VH>() {

        private var items = emptyList<NotificationEntity>()

        fun submit(newItems: List<NotificationEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.notifTitle)
            val body: TextView = v.findViewById(R.id.notifBody)
            val time: TextView = v.findViewById(R.id.notifTime)
            val dot: View = v.findViewById(R.id.notifDot)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            holder.title.text = n.title
            holder.body.text = n.body
            holder.time.text = SimpleDateFormat("d MMM • HH:mm", Locale.getDefault()).format(Date(n.timestamp))
            holder.dot.visibility = if (n.isRead) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(n) }
        }
    }
}
