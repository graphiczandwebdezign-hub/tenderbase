package com.tenderbase.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.ui.screens.DetailScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme

/**
 * Hosts the procurement workspace (spec §9). Opens by numeric extra (in-app
 * navigation) or by deep link (tenderbase://tender/{id}); a malformed link
 * never opens the wrong tender — it just declines with a friendly message.
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "tender_id"

        /** Resolve the tender to open from either launch shape. */
        fun tenderIdFrom(intent: Intent, uri: Uri?): Int? {
            intent.getIntExtra(EXTRA_ID, -1).takeIf { it > 0 }?.let { return it }
            return TenderActions.parseDeepLink(uri?.scheme, uri?.host, uri?.path)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = tenderIdFrom(intent, intent.data)
        if (id == null) {
            Toast.makeText(this, R.string.detail_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                DetailScreen(tenderId = id, onBack = { finish() })
            }
        }
    }
}
