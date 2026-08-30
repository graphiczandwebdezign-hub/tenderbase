package com.tenderbase.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tenderbase.app.databinding.ActivityDownloadsBinding
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDownloadsBinding
    private lateinit var adapter: DownloadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        adapter = DownloadAdapter(
            onOpen = { file -> openFile(file) },
            onShare = { file -> shareFile(file) },
            onDelete = { file -> deleteFile(file) }
        )

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        loadDownloads()
    }

    private fun loadDownloads() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }?.toList() ?: emptyList()
        adapter.submit(files)

        if (files.isEmpty()) {
            b.recycler.visibility = View.GONE
            b.emptyView.visibility = View.VISIBLE
            b.titleCount.text = "0 downloaded documents"
        } else {
            b.recycler.visibility = View.VISIBLE
            b.emptyView.visibility = View.GONE
            b.titleCount.text = resources.getQuantityString(
                R.plurals.tenders_count, files.size, files.size
            ).replace("tenders", "documents").replace("tender", "document")
        }
    }

    private fun openFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // No PDF viewer
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Document"))
        } catch (_: Exception) {}
    }

    private fun deleteFile(file: File) {
        try {
            file.delete()
            loadDownloads()
        } catch (_: Exception) {}
    }

    class DownloadAdapter(
        private var files: List<File>,
        private val onOpen: (File) -> Unit,
        private val onShare: (File) -> Unit,
        private val onDelete: (File) -> Unit
    ) : RecyclerView.Adapter<DownloadAdapter.VH>() {

        fun submit(newFiles: List<File>) {
            files = newFiles
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(v.resources.getIdentifier("fileName", "id", v.context.packageName))
            val meta: TextView = v.findViewById(v.resources.getIdentifier("fileMeta", "id", v.context.packageName))
            val btnOpen: MaterialButton = v.findViewById(v.resources.getIdentifier("btnOpen", "id", v.context.packageName))
            val btnShare: MaterialButton = v.findViewById(v.resources.getIdentifier("btnShare", "id", v.context.packageName))
            val btnDelete: MaterialButton = v.findViewById(v.resources.getIdentifier("btnDelete", "id", v.context.packageName))
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            return VH(v)
        }

        override fun getItemCount() = files.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            val sizeMb = String.format("%.1f MB", f.length() / (1024.0 * 1024.0))
            holder.name.text = f.name
            holder.meta.text = sizeMb
            holder.btnOpen.setOnClickListener { onOpen(f) }
            holder.btnShare.setOnClickListener { onShare(f) }
            holder.btnDelete.setOnClickListener { onDelete(f) }
        }
    }
}
