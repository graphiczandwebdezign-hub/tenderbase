package com.tenderbase.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.ui.screens.SavedSearchesScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme

/** Server-backed saved searches: alerts on/off, apply, delete. */
class SavedSearchesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                SavedSearchesScreen(
                    onBack = { finish() },
                    onApply = { filters ->
                        startActivity(
                            MainActivity.openTabIntent(this, "home")
                                .putExtra(
                                    MainActivity.EXTRA_APPLY_FILTERS,
                                    filters.toJson()
                                )
                        )
                        finish()
                    },
                    snack = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }
}
