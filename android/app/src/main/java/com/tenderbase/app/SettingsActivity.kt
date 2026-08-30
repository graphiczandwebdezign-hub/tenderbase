package com.tenderbase.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.tenderbase.app.ui.screens.SettingsScreen
import com.tenderbase.app.ui.screens.ThemeMode
import com.tenderbase.app.ui.theme.TenderBaseTheme

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                SettingsScreen(
                    onBack = { finish() },
                    openSavedSearches = {
                        startActivity(Intent(this, SavedSearchesActivity::class.java))
                    },
                    snack = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                    onThemeChanged = { mode ->
                        AppCompatDelegate.setDefaultNightMode(
                            when (mode) {
                                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            }
                        )
                    },
                )
            }
        }
    }
}
