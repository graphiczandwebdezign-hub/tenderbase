package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.ui.screens.DeadlinesScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme

/** Deadline command centre (spec §10) — countdown-first view of saved + closing work. */
class DeadlinesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                DeadlinesScreen(
                    onBack = { finish() },
                    openDetail = { id ->
                        startActivity(
                            Intent(this, DetailActivity::class.java)
                                .putExtra(DetailActivity.EXTRA_ID, id)
                        )
                    },
                    openAlerts = { startActivity(MainActivity.openTabIntent(this, "alerts")) },
                )
            }
        }
    }
}
