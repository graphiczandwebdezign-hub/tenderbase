package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.ui.screens.DownloadsScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme

/** The bid library: every document downloaded from a tender workspace. */
class DownloadsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                DownloadsScreen(
                    onBack = { finish() },
                    openTender = { id ->
                        startActivity(
                            Intent(this, DetailActivity::class.java)
                                .putExtra(DetailActivity.EXTRA_ID, id)
                        )
                    },
                    snack = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }
}
