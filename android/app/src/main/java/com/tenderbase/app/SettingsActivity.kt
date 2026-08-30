package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tenderbase.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var repo: TenderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)
        b.rowCategories.setOnClickListener {
            val i = Intent(this, PreferencesActivity::class.java)
            i.putExtra(PreferencesActivity.EXTRA_TYPE, PreferencesActivity.TYPE_CATEGORIES)
            startActivity(i)
        }

        b.rowProvinces.setOnClickListener {
            val i = Intent(this, PreferencesActivity::class.java)
            i.putExtra(PreferencesActivity.EXTRA_TYPE, PreferencesActivity.TYPE_PROVINCES)
            startActivity(i)
        }

        b.rowClearDownloads.setOnClickListener {
            clearDownloads()
        }

        b.rowHiddenTenders.setOnClickListener {
            repo.unhideAllTenders()
            Toast.makeText(this, R.string.hidden_tenders_none, Toast.LENGTH_SHORT).show()
            updateHiddenTendersStatus()
        }
        updateHiddenTendersStatus()

        checkApiStatus()
    }

    private fun updateHiddenTendersStatus() {
        val count = repo.hiddenTenderIds().size
        b.hiddenTendersStatus.text = if (count == 0) {
            getString(R.string.hidden_tenders_none)
        } else {
            getString(R.string.hidden_tenders_count, count)
        }
    }

    private fun clearDownloads() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }
            var count = 0
            files?.forEach {
                if (it.delete()) count++
            }
            Toast.makeText(this, "Cleared $count downloaded documents", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to clear documents", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkApiStatus() {
        b.apiStatusText.text = "Checking connection..."
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    val url = URL("${ApiClient.BASE_URL}/api/v1/health")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val code = conn.responseCode
                    code in 200..299
                } catch (_: Exception) {
                    false
                }
            }
            if (status) {
                b.apiStatusIndicator.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
                b.apiStatusText.text = "● Connected (Live API)"
            } else {
                b.apiStatusIndicator.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
                b.apiStatusText.text = "● Connection unavailable"
            }
        }
    }
}
