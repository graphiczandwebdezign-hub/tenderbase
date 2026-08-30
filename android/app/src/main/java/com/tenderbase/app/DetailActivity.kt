package com.tenderbase.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.tenderbase.app.databinding.ActivityDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DetailActivity : AppCompatActivity() {

    companion object { const val EXTRA_ID = "tender_id" }

    private lateinit var b: ActivityDetailBinding
    private lateinit var repo: TenderRepository
    private var tender: Tender? = null
    private var isSaved = false
    private var saveMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)

        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) { finish(); return }
        load(id)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        saveMenuItem = menu.findItem(R.id.action_save)
        updateSaveIcon()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                tender?.let { t ->
                    lifecycleScope.launch {
                        isSaved = repo.toggleSave(t)
                        updateSaveIcon()
                        Toast.makeText(
                            this@DetailActivity,
                            if (isSaved) "★ Tender saved" else "☆ Tender removed from saved",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                true
            }
            R.id.action_share -> {
                tender?.let { shareTender(it) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateSaveIcon() {
        saveMenuItem?.setIcon(
            if (isSaved) android.R.drawable.star_on else android.R.drawable.star_off
        )
        saveMenuItem?.title = if (isSaved) "Saved" else "Save"
    }

    private fun load(id: Int) {
        b.progress.visibility = View.VISIBLE
        b.content.visibility = View.GONE
        b.errorView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val t = ApiClient.fetchTender(id)
                tender = t
                isSaved = repo.isSaved(t.id)
                updateSaveIcon()
                bind(t)
                b.content.visibility = View.VISIBLE
            } catch (e: Exception) {
                // Try offline saved/cached
                val savedList = repo.getSavedTenders()
                val found = savedList.find { it.id == id }
                if (found != null) {
                    val t = found.toTender()
                    tender = t
                    isSaved = true
                    updateSaveIcon()
                    bind(t)
                    b.content.visibility = View.VISIBLE
                } else {
                    b.errorText.text = getString(R.string.error_body, e.message ?: "error")
                    b.errorView.visibility = View.VISIBLE
                }
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
            val chip = Chip(this).apply {
                text = c.replace('-', ' ').replaceFirstChar { it.uppercase() }
                isClickable = false
                isCheckable = false
            }
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
                val docView = layoutInflater.inflate(R.layout.item_document, b.docsContainer, false)
                val titleTv = docView.findViewById<TextView>(R.id.docTitle)
                val actionBtn = docView.findViewById<View>(R.id.docAction)
                titleTv.text = d.title
                
                // Check if already downloaded
                val fileName = "${t.id}_${d.title.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")}.pdf"
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                if (file.exists()) {
                    (actionBtn as? TextView)?.text = "✓ Open PDF"
                    docView.setOnClickListener { openPdfFile(file) }
                } else {
                    (actionBtn as? TextView)?.text = "Download"
                    docView.setOnClickListener { downloadDocument(d.title, d.url, fileName, docView) }
                }
                b.docsContainer.addView(docView)
            }
        }

        // Open on eTenders
        if (!t.sourceUrl.isNullOrBlank()) {
            b.openSource.visibility = View.VISIBLE
            b.openSource.setOnClickListener { openUrl(t.sourceUrl!!) }
        } else {
            b.openSource.visibility = View.GONE
        }
    }

    private fun downloadDocument(title: String, urlStr: String, fileName: String, view: View) {
        try {
            val request = DownloadManager.Request(Uri.parse(urlStr))
                .setTitle(title)
                .setDescription("Downloading tender document...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started...", Toast.LENGTH_SHORT).show()

            view.postDelayed({
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                if (file.exists()) {
                    (view.findViewById<TextView>(R.id.docAction))?.text = "✓ Open PDF"
                    view.setOnClickListener { openPdfFile(file) }
                }
            }, 2000)
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPdfFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTender(t: Tender) {
        val shareText = buildString {
            append("TenderBase Tender\n\n")
            append("${t.title}\n\n")
            append("Organisation: ${t.organisation ?: "—"}\n")
            append("Closing: ${DateUtils.prettyDate(t.closingAt, t.closingDate)}\n")
            if (!t.sourceUrl.isNullOrBlank()) {
                append("\nView: ${t.sourceUrl}")
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, t.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(intent, "Share Tender"))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }
}
