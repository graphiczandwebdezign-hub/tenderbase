package com.tenderbase.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bounded ring buffer of recent app events ("the last thing the user did
 * before it crashed"). Pure Kotlin so the exact capacity/FIFO semantics are
 * unit-tested on the JVM.
 */
class Breadcrumbs(private val capacity: Int = DEFAULT_CAPACITY) {

    private val deque = ArrayDeque<String>()

    @Synchronized
    fun add(entry: String) {
        if (capacity < 1) return
        while (deque.size >= capacity) deque.removeFirst()
        deque.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<String> = deque.toList()

    @Synchronized
    fun clear() = deque.clear()

    fun size(): Int = deque.size

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

/**
 * Zero-dependency crash capture (Sprint 0, audit finding C2).
 *
 * There is no Firebase/Crashlytics in this build (google-services is not
 * configured), so a fatal crash previously left no trace anywhere. This
 * reporter installs an uncaught-exception handler that writes a compact,
 * human-readable report — device, Android version, breadcrumbs, stack trace —
 * to app-private storage, then hands control back to the previous handler so
 * the system crash flow is unchanged.
 *
 * The user shares the report from Settings → "Share diagnostics": no ADB, no
 * permissions, no third-party SDK.
 */
object CrashReporter {

    private const val DIR = "crash_reports"
    private const val FILE_PREFIX = "crash-"
    private const val MAX_REPORTS = 5
    private const val SEPARATOR = "--------------------------------------------------"

    private val crumbs = Breadcrumbs()
    @Volatile
    private var installed = false

    /** Install once, as early as possible (Application.onCreate). */
    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeReport(app, thread, error) }
            // Chain: let the platform (or a future handler) keep its behaviour.
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        breadcrumb("app: process start (${versionInfo(app)})")
    }

    /** Record a durable, human-readable trail mark (last 64 kept). */
    fun breadcrumb(event: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        crumbs.add("$ts $event")
    }

    // ------------------------------------------------------------- reports

    fun hasReport(context: Context): Boolean = latestReport(context) != null

    /** Most recent crash report file, or null when the app has been healthy. */
    fun latestReport(context: Context): File? =
        reportsDir(context).listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.isFile && f.length() > 0 }
            ?.maxByOrNull { it.lastModified() }

    /** Epoch millis of the latest report (for the Settings subtitle), or null. */
    fun latestReportTime(context: Context): Long? = latestReport(context)?.lastModified()

    /**
     * Everything we would attach to a support message: app/device facts always,
     * the latest crash report when one exists. Never leaves the device unless
     * the user shares it (Settings → Share diagnostics).
     */
    fun diagnosticsText(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("TenderBase diagnostics")
        sb.appendLine(SEPARATOR)
        appendDeviceHeader(sb, context)
        val latest = latestReport(context)
        if (latest != null) {
            sb.appendLine()
            sb.appendLine("LATEST CRASH REPORT (${latest.name})")
            sb.appendLine(SEPARATOR)
            runCatching { sb.append(latest.readText()) }
                .onFailure { sb.appendLine("(failed to read report file)") }
        } else {
            sb.appendLine()
            sb.appendLine("No crash reports on this device.")
            sb.appendLine("Recent activity (breadcrumbs):")
            crumbs.snapshot().forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    /** Forget the breadcrumb trail (tests). */
    fun clearBreadcrumbs() = crumbs.clear()

    // ---------------------------------------------------------- internals

    private fun writeReport(app: Application, thread: Thread, error: Throwable) {
        val dir = reportsDir(app)
        dir.mkdirs()
        pruneOldReports(dir)
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$ts.txt")
        val sb = StringBuilder()
        appendDeviceHeader(sb, app)
        sb.appendLine()
        sb.appendLine("BREADCRUMBS (most recent last)")
        sb.appendLine(SEPARATOR)
        crumbs.snapshot().forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("FATAL EXCEPTION on thread \"${thread.name}\"")
        sb.appendLine(SEPARATOR)
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        sb.append(sw.toString())
        file.writeText(sb.toString())
    }

    private fun appendDeviceHeader(sb: StringBuilder, context: Context) {
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        sb.appendLine("time: ${utc.format(Date())}")
        sb.appendLine("app: ${context.packageName} (${versionInfo(context)})")
        sb.appendLine(
            "device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                "(Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        )
        sb.appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    private fun versionInfo(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${info.versionName}/${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}"
    }.getOrDefault("version unknown")

    private fun reportsDir(context: Context): File = File(context.filesDir, DIR)

    private fun pruneOldReports(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_REPORTS - 1) // newest (MAX_REPORTS-1) survive; +1 being written now
            .forEach { runCatching { it.delete() } }
    }
}
