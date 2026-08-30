package com.tenderbase.app

import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.databinding.ActivityPreferencesBinding

class PreferencesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "pref_type"
        const val TYPE_CATEGORIES = "categories"
        const val TYPE_PROVINCES = "provinces"
    }

    private lateinit var b: ActivityPreferencesBinding
    private lateinit var repo: TenderRepository
    private var type: String = TYPE_CATEGORIES

    private val categories = listOf(
        "Construction", "IT & Technology", "Supplies", "Cleaning",
        "Security", "Transport", "Engineering", "Medical",
        "Professional Services", "Agriculture", "Other"
    )

    private val provinces = listOf(
        "Eastern Cape", "Free State", "Gauteng", "KwaZulu-Natal",
        "Limpopo", "Mpumalanga", "Northern Cape", "North West",
        "Western Cape", "National"
    )

    private val checkboxes = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPreferencesBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = TenderRepository(this)
        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_CATEGORIES

        if (type == TYPE_CATEGORIES) {
            supportActionBar?.title = "My Categories"
            b.description.text = "Select the tender types you want to receive alerts for."
            populate(categories, repo.getSelectedCategories())
        } else {
            supportActionBar?.title = "My Provinces"
            b.description.text = "Select the provinces you want to monitor."
            populate(provinces, repo.getSelectedProvinces())
        }

        b.btnSave.setOnClickListener {
            save()
        }
    }

    private fun populate(items: List<String>, saved: Set<String>) {
        for (item in items) {
            val cb = CheckBox(this).apply {
                text = item
                isChecked = saved.isEmpty() || saved.contains(item)
                setPadding(8, 16, 8, 16)
                textSize = 15f
            }
            checkboxes[item] = cb
            b.container.addView(cb)
        }
    }

    private fun save() {
        val selected = checkboxes.filter { it.value.isChecked }.keys
        if (type == TYPE_CATEGORIES) {
            repo.setSelectedCategories(selected)
        } else {
            repo.setSelectedProvinces(selected)
        }
        finish()
    }
}
