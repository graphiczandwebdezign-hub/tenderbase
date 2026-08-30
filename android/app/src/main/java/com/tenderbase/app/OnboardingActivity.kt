package com.tenderbase.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tenderbase.app.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var b: ActivityOnboardingBinding
    private lateinit var repo: TenderRepository
    private var step = 0

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

    private val categoryCheckboxes = mutableMapOf<String, CheckBox>()
    private val provinceCheckboxes = mutableMapOf<String, CheckBox>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        finishOnboarding()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = TenderRepository(this)

        if (repo.isOnboarded()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupStep()

        b.btnNext.setOnClickListener {
            when (step) {
                0 -> { step = 1; setupStep() }
                1 -> { saveCategories(); step = 2; setupStep() }
                2 -> { saveProvinces(); step = 3; setupStep() }
                3 -> { step = 4; setupStep() }
                4 -> { requestNotificationPermission() }
            }
        }
    }

    private fun setupStep() {
        b.stepWelcome.visibility = if (step == 0) View.VISIBLE else View.GONE
        b.stepCategories.visibility = if (step == 1) View.VISIBLE else View.GONE
        b.stepProvinces.visibility = if (step == 2) View.VISIBLE else View.GONE
        b.stepNotificationInfo.visibility = if (step == 3) View.VISIBLE else View.GONE
        b.stepNotificationRequest.visibility = if (step == 4) View.VISIBLE else View.GONE

        when (step) {
            0 -> {
                b.title.text = "Welcome to TenderBase"
                b.subtitle.text = "Never miss a tender again."
                b.btnNext.text = "Continue"
            }
            1 -> {
                b.title.text = "Choose Categories"
                b.subtitle.text = "Select the tender types you want to monitor."
                b.btnNext.text = "Continue"
                populateCategories()
            }
            2 -> {
                b.title.text = "Choose Provinces"
                b.subtitle.text = "Select regions you operate in."
                b.btnNext.text = "Continue"
                populateProvinces()
            }
            3 -> {
                b.title.text = "Stay Ahead of Deadlines"
                b.subtitle.text = "TenderBase can notify you when new tenders matching your interests are published."
                b.btnNext.text = "Enable Notifications"
            }
            4 -> {
                b.title.text = "You're all set"
                b.subtitle.text = "TenderBase is watching for matching opportunities. We'll notify you when one appears."
                b.btnNext.text = "Start Exploring"
            }
        }
    }

    private fun populateCategories() {
        if (b.categoryContainer.childCount > 0) return
        val saved = repo.getSelectedCategories()
        for (cat in categories) {
            val cb = CheckBox(this).apply {
                text = cat
                isChecked = saved.isEmpty() || saved.contains(cat)
                textSize = 15.spToPx() // or standard
                setPadding(8, 16, 8, 16)
            }
            categoryCheckboxes[cat] = cb
            b.categoryContainer.addView(cb)
        }
    }

    private fun populateProvinces() {
        if (b.provinceContainer.childCount > 0) return
        val saved = repo.getSelectedProvinces()
        for (prov in provinces) {
            val cb = CheckBox(this).apply {
                text = prov
                isChecked = saved.isEmpty() || saved.contains(prov)
                setPadding(8, 16, 8, 16)
            }
            provinceCheckboxes[prov] = cb
            b.provinceContainer.addView(cb)
        }
    }

    private fun saveCategories() {
        val selected = categoryCheckboxes.filter { it.value.isChecked }.keys
        repo.setSelectedCategories(selected)
    }

    private fun saveProvinces() {
        val selected = provinceCheckboxes.filter { it.value.isChecked }.keys
        repo.setSelectedProvinces(selected)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                finishOnboarding()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        repo.setOnboarded(true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun Int.spToPx(): Float = this.toFloat()
}
