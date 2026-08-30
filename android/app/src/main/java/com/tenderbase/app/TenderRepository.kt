package com.tenderbase.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow

class TenderRepository(context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val dao = db.tenderDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("tenderbase_prefs", Context.MODE_PRIVATE)

    val savedTendersFlow: Flow<List<SavedTenderEntity>> = dao.getSavedTendersFlow()
    val notificationHistoryFlow: Flow<List<NotificationEntity>> = dao.getNotificationsFlow()
    val unreadCountFlow: Flow<Int> = dao.getUnreadCountFlow()

    suspend fun isSaved(id: Int): Boolean = dao.isSaved(id)

    suspend fun toggleSave(tender: Tender): Boolean {
        val saved = dao.isSaved(tender.id)
        if (saved) {
            dao.removeSavedTender(tender.id)
            return false
        } else {
            dao.saveTender(SavedTenderEntity.fromTender(tender))
            return true
        }
    }

    suspend fun removeSaved(id: Int) = dao.removeSavedTender(id)

    suspend fun getSavedTenders(): List<SavedTenderEntity> = dao.getSavedTenders()

    suspend fun addNotification(tenderId: Int, title: String, body: String) {
        dao.insertNotification(NotificationEntity(tenderId = tenderId, title = title, body = body))
    }

    suspend fun markNotificationRead(id: Long) = dao.markNotificationRead(id)
    suspend fun markAllNotificationsRead() = dao.markAllNotificationsRead()

    // Preferences: Categories and Provinces
    fun getSelectedCategories(): Set<String> {
        return prefs.getStringSet("selected_categories", emptySet()) ?: emptySet()
    }

    fun setSelectedCategories(categories: Set<String>) {
        prefs.edit().putStringSet("selected_categories", categories).apply()
    }

    fun getSelectedProvinces(): Set<String> {
        return prefs.getStringSet("selected_provinces", emptySet()) ?: emptySet()
    }

    fun setSelectedProvinces(provinces: Set<String>) {
        prefs.edit().putStringSet("selected_provinces", provinces).apply()
    }

    fun isOnboarded(): Boolean = prefs.getBoolean("is_onboarded", false)
    fun setOnboarded(onboarded: Boolean) = prefs.edit().putBoolean("is_onboarded", onboarded).apply()

    // Offline Cache
    suspend fun cacheTenders(tenders: List<Tender>) {
        val entities = tenders.map { t ->
            CachedTenderEntity(
                id = t.id,
                title = t.title,
                organisation = t.organisation,
                province = t.province,
                category = t.category ?: t.categories.firstOrNull(),
                closingDate = t.closingDate,
                closingAt = t.closingAt,
                sourceUrl = t.sourceUrl,
                jsonPayload = ""
            )
        }
        dao.clearCachedTenders()
        dao.insertCachedTenders(entities)
    }

    suspend fun getCachedTenders(): List<Tender> {
        return dao.getCachedTenders().map { c ->
            Tender(
                id = c.id,
                title = c.title,
                description = null,
                organisation = c.organisation,
                province = c.province,
                category = c.category,
                categories = c.category?.let { listOf(it) } ?: emptyList(),
                tenderType = null,
                status = null,
                closingDate = c.closingDate,
                closingAt = c.closingAt,
                sourceUrl = c.sourceUrl,
                documents = emptyList()
            )
        }
    }
}
