package com.tenderbase.app

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where downloaded tender documents live and how they are named — shared by
 * the detail screen (download) and the downloads library so the two never
 * disagree. Kept off the ViewModel/UI layers to stay testable and reusable.
 */
object DocumentStore {

    val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "zip", "txt", "rtf")

    fun dir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    fun fileFor(context: Context, tenderId: Int, title: String, url: String, mime: String?): File {
        val safeTitle = title.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")
        val base = "${tenderId}_$safeTitle"
        val urlExt = url.substringBefore('?').substringAfterLast('.', "")
            .lowercase().takeIf { it.isNotEmpty() && it.length <= 5 }
        val mimeExt = mime?.substringAfter('/')?.takeWhile { it.isLetterOrDigit() }?.lowercase()
        val ext = urlExt ?: mimeExt ?: "pdf"
        return File(dir(context), "$base.$ext")
    }

    fun findExisting(context: Context, name: String): File? =
        dir(context).listFiles()?.firstOrNull { it.name == name && it.length() > 0 }

    /** Library listing: documents-only, newest first. */
    fun listDocuments(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.lowercase() in DOCUMENT_EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun humanName(file: File): String {
        // "1234_Supply_of_D__hardware.pdf" → "Supply of D  hardware"
        val noExt = file.nameWithoutExtension
        val underscore = noExt.indexOf('_')
        val raw = if (underscore > 0) noExt.substring(underscore + 1) else noExt
        return raw.replace('_', ' ').ifBlank { file.name }
    }
}
