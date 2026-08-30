package com.tenderbase.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.tenderbase.app.databinding.ActivityDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
                val fileName = fileNameFor(t.id, d.title, d.url, d.mime)
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                if (file.exists()) {
                    (actionBtn as? TextView)?.text = "✓ Open PDF"
                    docView.setOnClickListener { openFile(file) }
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
        val actionBtn = view.findViewById<TextView>(R.id.docAction)
        val target = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        target.parentFile?.mkdirs()

        actionBtn?.text = "Downloading…"
        view.setOnClickListener(null)

        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { downloadToFile(urlStr, target) }
                if (ok && target.exists() && target.length() > 0) {
                    actionBtn?.text = "✓ Open PDF"
                    view.setOnClickListener { openFile(target) }
                    Toast.makeText(this@DetailActivity, "Downloaded ✓", Toast.LENGTH_SHORT).show()
                } else {
                    target.delete()
                    resetDownloadAction(actionBtn, view, title, urlStr, fileName)
                    Toast.makeText(this@DetailActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                target.delete()
                resetDownloadAction(actionBtn, view, title, urlStr, fileName)
                Toast.makeText(this@DetailActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Restores the row to a tappable "Download" state after a failure. */
    private fun resetDownloadAction(
        actionBtn: TextView?,
        view: View,
        title: String,
        urlStr: String,
        fileName: String
    ) {
        actionBtn?.text = "Download"
        view.setOnClickListener { downloadDocument(title, urlStr, fileName, view) }
    }

    /** Downloads the file with a browser-like UA and follows redirects. */
    private fun downloadToFile(urlStr: String, target: File): Boolean {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android; TenderBase) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", "*/*")
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** Sensible, extension-correct file name derived from the source document. */
    private fun fileNameFor(tenderId: Int, title: String, url: String, mime: String?): String {
        val safeTitle = title.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")
        val base = "${tenderId}_$safeTitle"
        val urlExt = url.substringBefore('?').substringAfterLast('.', "")
            .lowercase().takeIf { it.isNotEmpty() && it.length <= 5 }
        val mimeExt = mime?.substringAfter('/')?.takeWhile { it.isLetterOrDigit() }?.lowercase()
        val ext = urlExt ?: mimeExt ?: "pdf"
        return "$base.$ext"
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: if (file.extension.lowercase() == "pdf") "application/pdf"
                   else "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open document"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
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
