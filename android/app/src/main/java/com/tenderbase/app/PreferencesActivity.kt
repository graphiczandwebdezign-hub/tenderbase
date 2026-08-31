package com.tenderbase.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.ui.screens.PreferencesScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme

/** Category / province preference editors, hosted from More and Settings. */
class PreferencesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "pref_type"
        const val TYPE_CATEGORIES = "categories"
        const val TYPE_PROVINCES = "provinces"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_CATEGORIES
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                PreferencesScreen(
                    type = type,
                    onBack = { finish() },
                    onSaved = { finish() },
                )
            }
        }
    }
}
