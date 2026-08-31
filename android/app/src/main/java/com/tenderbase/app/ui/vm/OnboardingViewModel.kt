package com.tenderbase.app.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tenderbase.app.OnboardingLogic
import com.tenderbase.app.TenderRepository
import com.tenderbase.app.TenderTaxonomy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Onboarding state machine (Sprint 1, audit finding H1).
 *
 * Previously the pager screen held selections in `rememberSaveable` and built
 * a repository from the Activity context mid-composition; the state now lives
 * here on the application context, survives configuration changes and process
 * death startup paths cleanly, and is written to preferences exactly once —
 * when the user leaves the flow — instead of on every Continue tap.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TenderRepository(app.applicationContext)

    val pageCount: Int = 4

    private val _categories = MutableStateFlow(repo.getSelectedCategories().sorted())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _provinces = MutableStateFlow(repo.getSelectedProvinces().sorted())
    val provinces: StateFlow<List<String>> = _provinces.asStateFlow()

    fun toggleCategory(name: String) {
        _categories.value = OnboardingLogic.toggle(_categories.value, name)
    }

    fun toggleProvince(name: String) {
        _provinces.value = OnboardingLogic.toggle(_provinces.value, name)
    }

    fun selectAllCategories() {
        _categories.value = OnboardingLogic.selectAll(TenderTaxonomy.CATEGORIES)
    }

    fun selectAllProvinces() {
        _provinces.value = OnboardingLogic.selectAll(TenderTaxonomy.PROVINCES)
    }

    /** Whether the flow has already been completed (guards double-finish). */
    fun isOnboarded(): Boolean = repo.isOnboarded()

    /**
     * The single persistence point: selections + onboarded flag, written once
     * when the flow exits (Skip, Maybe later, or after the permission answer).
     */
    fun completeOnboarding() {
        repo.setSelectedCategories(_categories.value.toSet())
        repo.setSelectedProvinces(_provinces.value.toSet())
        repo.setOnboarded(true)
        repo.setNotifPermissionAsked()
    }
}
