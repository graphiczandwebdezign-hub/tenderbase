package com.tenderbase.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

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
            // Back up the save server-side so the workspace can sync and
            // deadline reminders reach this device. Best-effort.
            saveOnServer(tender.id)
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

    /**
     * Stable per-install id (created on first use). Identifies this install to
     * the backend for saved searches and push notifications.
     */
    fun clientId(): String {
        var id = prefs.getString("install_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("install_id", id).apply()
        }
        return id
    }

    /** Register this install's FCM token so saved-search alerts can reach it. */
    suspend fun registerDevice(token: String) {
        try {
            ApiClient.registerDevice(clientId(), token)
        } catch (_: Exception) {
            // Registration is best-effort; retried on the next token refresh.
        }
    }

    // ------------------------------------------------------- hidden tenders
    // Personal dismissals: ids the user swiped away on the discovery list.
    // Stored locally (never sent to the server — they affect no one else).

    fun hiddenTenderIds(): Set<Int> =
        prefs.getStringSet("hidden_tender_ids", emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()

    fun hideTender(id: Int) {
        prefs.edit().putStringSet(
            "hidden_tender_ids",
            (hiddenTenderIds() + id).map { it.toString() }.toSet()
        ).apply()
    }

    fun unhideAllTenders() {
        prefs.edit().remove("hidden_tender_ids").apply()
    }

    // Local deadline reminders (Sprint 6): tenders already reminded offline.
    fun remindedTenderIds(): Set<Int> =
        prefs.getStringSet("reminded_tender_ids", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

    // -------------------------------------------------- recent searches (1.2)

    fun recentSearches(): List<String> =
        RecentSearches.decode(prefs.getString("recent_searches", null))

    fun addRecentSearch(query: String) {
        if (query.isBlank()) return
        prefs.edit()
            .putString("recent_searches", RecentSearches.add(prefs.getString("recent_searches", null), query))
            .apply()
    }

    fun removeRecentSearch(query: String) {
        prefs.edit()
            .putString("recent_searches", RecentSearches.remove(prefs.getString("recent_searches", null), query))
            .apply()
    }

    fun clearRecentSearches() {
        prefs.edit().putString("recent_searches", RecentSearches.clear()).apply()
    }

    // ------------------------------------------------ last feed update (1.2)

    /** Timestamp of the last successful live fetch ("Updated 12 min ago"). */
    fun lastFeedUpdate(): Long = prefs.getLong("last_feed_update", 0L)

    fun setLastFeedUpdate(ts: Long) {
        prefs.edit().putLong("last_feed_update", ts).apply()
    }

    fun markReminded(id: Int) {
        prefs.edit()
            .putStringSet(
                "reminded_tender_ids",
                (remindedTenderIds() + id).map { it.toString() }.toSet()
            )
            .apply()
    }

    // ------------------------------------------------------- bid workspace

    fun noteFlow(tenderId: Int): Flow<TenderNoteEntity?> = dao.noteFlow(tenderId)

    fun checklistFlow(tenderId: Int): Flow<List<ChecklistItemEntity>> = dao.checklistFlow(tenderId)

    /** Checklist totals per tender for the saved list (live Room flow). */
    fun checklistStatsFlow(): Flow<List<ChecklistStats>> = dao.checklistStatsFlow()

    /** Ids of tenders with a workspace note. */
    fun notedTenderIdsFlow(): Flow<Set<Int>> =
        dao.notedTenderIdsFlow().map { it.toSet() }

    suspend fun renameChecklistItem(id: Long, label: String) {
        if (label.isNotBlank()) dao.renameChecklistItem(id, label.trim())
    }

    /** Swap an item with its neighbour (up = toward the top). No-op at edges. */
    suspend fun moveChecklistItem(tenderId: Int, id: Long, up: Boolean) {
        val items = dao.checklistItems(tenderId)
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val swapWith = if (up) idx - 1 else idx + 1
        if (swapWith !in items.indices) return
        val a = items[idx]
        val b = items[swapWith]
        dao.setChecklistPosition(a.id, b.position)
        dao.setChecklistPosition(b.id, a.position)
    }

    suspend fun saveNote(tenderId: Int, text: String) {
        if (text.isBlank()) {
            dao.deleteNote(tenderId)
        } else {
            dao.upsertNote(TenderNoteEntity(tenderId = tenderId, note = text.trim()))
        }
    }

    suspend fun addChecklistItem(tenderId: Int, label: String) {
        if (label.isBlank()) return
        dao.insertChecklistItem(
            ChecklistItemEntity(
                tenderId = tenderId,
                label = label.trim(),
                position = dao.nextChecklistPosition(tenderId)
            )
        )
    }

    suspend fun setChecklistDone(id: Long, done: Boolean) = dao.setChecklistDone(id, done)

    suspend fun deleteChecklistItem(id: Long) = dao.deleteChecklistItem(id)

    /** Replace the local checklist with the given items (restore path). */
    suspend fun replaceChecklist(tenderId: Int, items: List<Pair<String, Boolean>>) {
        dao.deleteChecklistFor(tenderId)
        items.forEachIndexed { index, (label, done) ->
            dao.insertChecklistItem(
                ChecklistItemEntity(
                    tenderId = tenderId, label = label, isDone = done, position = index
                )
            )
        }
    }

    // -------------------------------------------------- server sync (Sprint 6)

    /**
     * Push a saved tender's workspace to the server. Best-effort: the local
     * Room data stays the source of truth; failures are silent (the next
     * mutation or restore retries).
     */
    suspend fun pushWorkspace(tenderId: Int, note: String?, checklist: List<Pair<String, Boolean>>) {
        try {
            ApiClient.putWorkspace(clientId(), tenderId, note, checklist)
        } catch (_: Exception) {
        }
    }

    /** Mark a tender saved server-side so its workspace can back up. */
    suspend fun saveOnServer(tenderId: Int) {
        try {
            ApiClient.saveTenderOnServer(clientId(), tenderId)
        } catch (_: Exception) {
        }
    }

    /**
     * Restore workspaces from the server into local Room. Only tenders already
     * saved locally are restored (the server list carries ids, not full
     * tenders). Returns the number of restored workspaces.
     */
    suspend fun restoreWorkspacesFromServer(): Int {
        val entries = try {
            ApiClient.fetchSavedWorkspace(clientId())
        } catch (_: Exception) {
            return -1
        }
        val savedIds = dao.getSavedTenders().map { it.id }.toSet()
        var restored = 0
        for (e in entries) {
            if (e.tenderId !in savedIds) continue
            if (e.note != null) dao.upsertNote(
                TenderNoteEntity(tenderId = e.tenderId, note = e.note)
            )
            if (e.checklist.isNotEmpty()) {
                replaceChecklist(e.tenderId, e.checklist)
            }
            restored++
        }
        return restored
    }

    // --------------------------------------- offline saved-search queue (S6)

    fun queueSavedSearch(name: String, payloadJson: String) {
        val current = prefs.getString("pending_saved_searches", null)
        prefs.edit()
            .putString("pending_saved_searches", SearchQueue.add(current, name, payloadJson))
            .apply()
    }

    /**
     * Try to create every queued saved search. Entries that succeed (or clash
     * with an existing name — 409) are removed; network failures stay queued.
     * Returns how many entries synced.
     */
    suspend fun flushSavedSearchQueue(): Int {
        var synced = 0
        while (true) {
            val json = prefs.getString("pending_saved_searches", null) ?: break
            val entries = SearchQueue.decode(json)
            if (entries.isEmpty()) break
            val entry = entries.first()
            try {
                ApiClient.createSavedSearch(
                    clientId(), entry.name, JSONObject(entry.payload)
                )
            } catch (e: ApiClient.ApiException) {
                if (e.statusCode == 409) {
                    // Already saved server-side: drop the queued copy.
                    prefs.edit()
                        .putString("pending_saved_searches", SearchQueue.remove(json, entry.name))
                        .apply()
                    continue
                }
                break // network/server error: keep the queue for later
            } catch (_: Exception) {
                break
            }
            prefs.edit()
                .putString("pending_saved_searches", SearchQueue.remove(json, entry.name))
                .apply()
            synced++
        }
        return synced
    }

    /** Seed the default checklist once, when a workspace is first opened. */
    suspend fun ensureDefaultChecklist(tenderId: Int) {
        if (dao.checklistCount(tenderId) > 0) return
        BidPack.defaultChecklist().forEachIndexed { index, label ->
            dao.insertChecklistItem(
                ChecklistItemEntity(tenderId = tenderId, label = label, position = index)
            )
        }
    }

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
