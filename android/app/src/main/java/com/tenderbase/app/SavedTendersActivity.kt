package com.tenderbase.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Kept for external deep links / old shortcuts — the tab replaced this screen. */
class SavedTendersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.openTabIntent(this, "saved"))
        finish()
    }
}
