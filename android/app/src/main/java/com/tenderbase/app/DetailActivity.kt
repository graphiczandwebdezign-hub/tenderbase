package com.tenderbase.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.CalendarContract
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tenderbase.app.databinding.ActivityDetailBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tender detail: the FIND → ACT step of the discovery loop.
 *
 * Opens by numeric extra (in-app navigation) or by deep link
 * (tenderbase://tender/{id}); a malformed link never opens the wrong tender.
 * Deadline actions: save, share-with-summary, add deadline to calendar.
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "tender_id"

        /** Resolve the tender to open from either launch shape. */
        fun tenderIdFrom(intent: Intent, uri: Uri?): Int? {
            intent.getIntExtra(EXTRA_ID, -1).takeIf { it > 0 }?.let { return it }
            return TenderActions.parseDeepLink(uri?.scheme, uri?.host, uri?.path)
        }
    }

    private lateinit var b: ActivityDetailBinding
    private lateinit var repo: TenderRepository
    private var tender: Tender? = null
    private var tenderId: Int = -1
    private var isSaved = false
    private var saveMenuItem: MenuItem? = null

    // Bid workspace state (local Room data for this tender).
    private var workspaceNote: String? = null
    private var workspaceChecklist: List<ChecklistItemEntity> = emptyList()

    // Debounced server backup of the workspace (only for saved tenders).
    private var workspaceSyncJob: Job? = null
    private var lastPushedState: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)

        val id = tenderIdFrom(intent, intent.data)
        if (id == null) { finish(); return }
        tenderId = id

        b.errorRetry.setOnClickListener { load(id) }
        b.addToCalendar.setOnClickListener { addDeadlineToCalendar() }
        setupWorkspace(id)
        load(id)
    }

    // -------------------------------------------------------- bid workspace

    /** Local flows: note + checklist for this tender (seeded once). */
    private fun setupWorkspace(id: Int) {
        lifecycleScope.launch { repo.ensureDefaultChecklist(id) }
        lifecycleScope.launch {
            repo.noteFlow(id).collectLatest { note ->
                workspaceNote = note?.note
                renderWorkspaceNote()
                scheduleWorkspaceBackup()
            }
        }
        lifecycleScope.launch {
            repo.checklistFlow(id).collectLatest { items ->
                workspaceChecklist = items
                renderChecklist()
                scheduleWorkspaceBackup()
            }
        }
        b.workspaceNote.setOnClickListener { showNoteDialog() }
        b.addChecklistButton.setOnClickListener { showAddChecklistItemDialog() }
        b.shareBidPack.setOnClickListener { shareBidPack() }
    }

    private fun renderWorkspaceNote() {
        val note = workspaceNote
        b.workspaceNote.text = note?.takeIf { it.isNotBlank() } ?: getString(R.string.note_empty)
        b.workspaceNote.paint.isItalic = note.isNullOrBlank()
    }

    private fun renderChecklist() {
        val items = workspaceChecklist
        val done = items.count { it.isDone }
        b.workspaceProgress.text = BidPack.progressLabel(done, items.size)

        b.checklistContainer.removeAllViews()
        for (item in items) {
            val box = layoutInflater.inflate(
                R.layout.item_checklist, b.checklistContainer, false
            ) as MaterialCheckBox
            box.text = item.label
            box.isChecked = item.isDone
            box.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { repo.setChecklistDone(item.id, checked) }
            }
            box.setOnLongClickListener {
                confirmDeleteChecklistItem(item)
                true
            }
            b.checklistContainer.addView(box)
        }
    }

    /**
     * Back the workspace up to the server (saved tenders only), debounced so a
     * burst of checkbox taps produces one PUT. The Room data stays the source
     * of truth; failures are silent and retried on the next change.
     */
    private fun scheduleWorkspaceBackup() {
        if (!isSaved) return
        val checklist = workspaceChecklist.map { it.label to it.isDone }
        val state = (workspaceNote.orEmpty()) + "|" +
            checklist.joinToString(";") { "${'$'}{it.first}=${'$'}{it.second}" }
        if (state == lastPushedState) return
        workspaceSyncJob?.cancel()
        workspaceSyncJob = lifecycleScope.launch {
            delay(800)
            try {
                repo.pushWorkspace(tenderId, workspaceNote, checklist)
                lastPushedState = state
            } catch (_: Exception) {
                // Best-effort only.
            }
        }
    }

    private fun confirmDeleteChecklistItem(item: ChecklistItemEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.checklist_delete_title))
            .setMessage(item.label)
            .setPositiveButton(getString(R.string.checklist_delete_confirm)) { _, _ ->
                lifecycleScope.launch { repo.deleteChecklistItem(item.id) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showNoteDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.note_hint)
            setText(workspaceNote.orEmpty())
            minLines = 3
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.note_edit_title))
            .setView(container)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val text = input.text?.toString().orEmpty()
                lifecycleScope.launch { repo.saveNote(tenderId, text) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
        if (!workspaceNote.isNullOrBlank()) {
            dialog.setNeutralButton(getString(R.string.note_clear)) { _, _ ->
                lifecycleScope.launch { repo.saveNote(tenderId, "") }
            }
        }
        dialog.show()
    }

    private fun showAddChecklistItemDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.checklist_add_hint)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.checklist_add))
            .setView(container)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val label = input.text?.toString().orEmpty()
                if (label.isNotBlank()) {
                    lifecycleScope.launch { repo.addChecklistItem(tenderId, label) }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun shareBidPack() {
        val t = tender ?: return
        val checklist = workspaceChecklist.map { it.label to it.isDone }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bid pack: ${t.title}")
            putExtra(Intent.EXTRA_TEXT, BidPack.build(t, workspaceNote, checklist))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_bid_pack)))
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
                    b.errorText.setText(R.string.error_body_generic)
                    b.errorView.visibility = View.VISIBLE
                }
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun bind(t: Tender) {
        // Status badge + source (same presentation as the discovery card).
        val badgeLabel = t.badgeLabel()
        b.statusBadge.text = badgeLabel
        val (bg, fg) = when (badgeLabel) {
            "OPEN" -> R.drawable.bg_badge_open to R.color.badgeOpenText
            "CLOSING SOON" -> R.drawable.bg_badge_soon to R.color.badgeSoonText
            "CANCELLED" -> R.drawable.bg_badge_cancelled to R.color.badgeCancelledText
            else -> R.drawable.bg_badge_closed to R.color.badgeClosedText
        }
        b.statusBadge.setBackgroundResource(bg)
        b.statusBadge.setTextColor(getColor(fg))
        b.sourceText.text = t.source ?: ""

        b.title.text = t.title
        b.org.text = t.organisation ?: "Unknown organisation"
        if (t.reference != null) {
            b.refText.visibility = View.VISIBLE
            b.refText.text = getString(R.string.ref_prefix, t.reference)
        } else {
            b.refText.visibility = View.GONE
        }

        // Deadline card: full date + urgency countdown from server state.
        b.closingDateBig.text = fullClosingDate(t)
        val countdown = DateUtils.closesLabel(t.closingAt, t.closingDate, t.deadlineState)
        val timePart = closingTime(t)?.let { " · $it" } ?: ""
        b.closingCountdown.text = countdown + timePart
        val urgency = DateUtils.urgency(t.closingAt, t.closingDate, t.deadlineState)
        b.closingCountdown.setTextColor(
            getColor(
                when (urgency) {
                    DateUtils.Urgency.CLOSED -> R.color.textMuted
                    DateUtils.Urgency.TODAY, DateUtils.Urgency.URGENT -> R.color.urgent
                    DateUtils.Urgency.SOON -> R.color.warning
                    DateUtils.Urgency.NORMAL -> R.color.primary
                }
            )
        )
        b.addToCalendar.visibility =
            if (urgency == DateUtils.Urgency.CLOSED) View.GONE else View.VISIBLE

        // Location / category tags
        val locationText = listOfNotNull(t.province, t.municipality).joinToString(" · ")
        b.locationTag.visibility = if (locationText.isEmpty()) View.GONE else View.VISIBLE
        b.locationTag.text = locationText
        val categoryText = t.category
            ?: t.categories.firstOrNull()?.replace('-', ' ')?.replaceFirstChar { it.uppercase() }
        b.categoryTag.visibility = if (categoryText == null) View.GONE else View.VISIBLE
        b.categoryTag.text = categoryText

        // Meta grid
        b.metaType.text = t.tenderType ?: "—"
        b.metaStatus.text = badgeLabel.capitalizeWords()
        b.metaPublished.text = t.advertisedDate?.prettyIsoDate() ?: "—"
        b.metaSubmission.text = t.submissionMethod?.capitalizeWords() ?: "—"

        b.description.text = t.description ?: "No description provided."

        // Amendments (amended tenders only)
        bindAmendments(t)

        // Documents, grouped by type (notices / addenda / annexures / other)
        b.docsContainer.removeAllViews()
        if (t.documents.isEmpty()) {
            b.docsLabel.visibility = View.GONE
            b.noDocs.visibility = View.VISIBLE
        } else {
            b.docsLabel.visibility = View.VISIBLE
            b.noDocs.visibility = View.GONE
            for (group in BidPack.groupDocuments(t.documents)) {
                val header = layoutInflater.inflate(
                    R.layout.item_section_header, b.docsContainer, false
                ) as TextView
                header.text = getString(R.string.doc_group_title, group.title, group.documents.size)
                b.docsContainer.addView(header)
                for (d in group.documents) {
                    val docView = layoutInflater.inflate(R.layout.item_document, b.docsContainer, false)
                    val titleTv = docView.findViewById<TextView>(R.id.docTitle)
                    val metaTv = docView.findViewById<TextView>(R.id.docMeta)
                    val actionBtn = docView.findViewById<View>(R.id.docAction)
                    titleTv.text = d.title
                    val mimeLabel = d.mime?.substringAfter('/')?.uppercase() ?: "FILE"
                    val size = TenderActions.formatFileSize(d.fileSize)
                    metaTv.text = "$mimeLabel${size?.let { " · $it" } ?: ""}"

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
        }

        // Open on eTenders
        if (!t.sourceUrl.isNullOrBlank()) {
            b.openSource.visibility = View.VISIBLE
            b.openSource.setOnClickListener { openUrl(t.sourceUrl!!) }
        } else {
            b.openSource.visibility = View.GONE
        }
    }

    private fun bindAmendments(t: Tender) {
        if (t.amendments.isEmpty()) {
            b.amendmentsLabel.visibility = View.GONE
            b.amendmentsContainer.visibility = View.GONE
            return
        }
        b.amendmentsLabel.visibility = View.VISIBLE
        b.amendmentsContainer.visibility = View.VISIBLE
        b.amendmentsContainer.removeAllViews()
        for (a in t.amendments) {
            val row = layoutInflater.inflate(R.layout.item_amendment, b.amendmentsContainer, false)
            row.findViewById<TextView>(R.id.amendmentField).text =
                a.fieldChanged.replace('_', ' ').trim().capitalizeWords()
            row.findViewById<TextView>(R.id.amendmentDate).text =
                a.detectedAt?.prettyIsoDate() ?: ""
            row.findViewById<TextView>(R.id.amendmentChange).text =
                getString(
                    R.string.amendment_change,
                    a.oldValue ?: "—",
                    a.newValue ?: "—"
                )
            b.amendmentsContainer.addView(row)
        }
    }

    // ------------------------------------------------------------- actions

    /** Offer the deadline as a calendar event (no permission needed: the
     * calendar app confirms the insert). */
    private fun addDeadlineToCalendar() {
        val t = tender ?: return
        val slot = TenderActions.calendarSlot(t)
        if (slot == null) {
            Toast.makeText(this, R.string.calendar_no_deadline, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, slot.beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, slot.endMillis)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, slot.allDay)
            putExtra(
                CalendarContract.Events.TITLE,
                "Tender deadline: ${t.title}"
            )
            putExtra(
                CalendarContract.Events.DESCRIPTION,
                TenderActions.shareSummary(t)
            )
            t.organisation?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            putExtra(
                CalendarContract.Events.AVAILABILITY,
                CalendarContract.Events.AVAILABILITY_BUSY
            )
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.calendar_no_app, Toast.LENGTH_SHORT).show()
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
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, t.title)
            putExtra(Intent.EXTRA_TEXT, TenderActions.shareSummary(t))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------- helpers

    private fun fullClosingDate(t: Tender): String {
        val millis = DateUtils.toMillis(t.closingAt, t.closingDate) ?: return "—"
        return SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(millis))
    }

    private fun closingTime(t: Tender): String? {
        if (t.closingAt == null) return null
        val millis = DateUtils.toMillis(t.closingAt, t.closingDate) ?: return null
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun String.capitalizeWords(): String =
        lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }

    private fun String.prettyIsoDate(): String = try {
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        outFmt.format(inFmt.parse(this)!!)
    } catch (_: Exception) {
        this
    }
}
