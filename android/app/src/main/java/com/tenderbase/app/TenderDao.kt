package com.tenderbase.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TenderDao {

    @Query("SELECT * FROM saved_tenders ORDER BY savedAt DESC")
    fun getSavedTendersFlow(): Flow<List<SavedTenderEntity>>

    @Query("SELECT * FROM saved_tenders ORDER BY savedAt DESC")
    suspend fun getSavedTenders(): List<SavedTenderEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_tenders WHERE id = :id)")
    suspend fun isSaved(id: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTender(tender: SavedTenderEntity)

    @Query("DELETE FROM saved_tenders WHERE id = :id")
    suspend fun removeSavedTender(id: Int)

    // Notifications
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getNotificationsFlow(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    suspend fun getNotifications(): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun getUnreadCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markNotificationRead(id: Long)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllNotificationsRead()

    // Cached Tenders for offline mode
    @Query("SELECT * FROM cached_tenders ORDER BY cachedAt DESC")
    suspend fun getCachedTenders(): List<CachedTenderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedTenders(tenders: List<CachedTenderEntity>)

    @Query("DELETE FROM cached_tenders")
    suspend fun clearCachedTenders()

    // ------------------------------------------------- bid workspace (Sprint 5)

    @Query("SELECT * FROM tender_notes WHERE tenderId = :tenderId")
    fun noteFlow(tenderId: Int): Flow<TenderNoteEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: TenderNoteEntity)

    @Query("DELETE FROM tender_notes WHERE tenderId = :tenderId")
    suspend fun deleteNote(tenderId: Int)

    @Query("SELECT * FROM checklist_items WHERE tenderId = :tenderId ORDER BY position ASC, id ASC")
    fun checklistFlow(tenderId: Int): Flow<List<ChecklistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(item: ChecklistItemEntity): Long

    @Query("UPDATE checklist_items SET isDone = :done WHERE id = :id")
    suspend fun setChecklistDone(id: Long, done: Boolean)

    @Query("DELETE FROM checklist_items WHERE id = :id")
    suspend fun deleteChecklistItem(id: Long)

    @Query("DELETE FROM checklist_items WHERE tenderId = :tenderId")
    suspend fun deleteChecklistFor(tenderId: Int)

    @Query("SELECT COUNT(*) FROM checklist_items WHERE tenderId = :tenderId")
    suspend fun checklistCount(tenderId: Int): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM checklist_items WHERE tenderId = :tenderId")
    suspend fun nextChecklistPosition(tenderId: Int): Int

    @Query("UPDATE checklist_items SET label = :label WHERE id = :id")
    suspend fun renameChecklistItem(id: Long, label: String)

    @Query("UPDATE checklist_items SET position = :position WHERE id = :id")
    suspend fun setChecklistPosition(id: Long, position: Int)

    @Query("SELECT * FROM checklist_items WHERE tenderId = :tenderId ORDER BY position ASC, id ASC")
    suspend fun checklistItems(tenderId: Int): List<ChecklistItemEntity>

    /** Per-tender completion stats so the Saved list can show bid progress. */
    @Query(
        "SELECT tenderId AS tenderId, COUNT(*) AS total, " +
            "SUM(CASE WHEN isDone THEN 1 ELSE 0 END) AS done " +
            "FROM checklist_items GROUP BY tenderId"
    )
    fun checklistStatsFlow(): Flow<List<ChecklistStats>>

    /** Tenders that have a workspace note (drives the saved-list note dot). */
    @Query("SELECT tenderId FROM tender_notes")
    fun notedTenderIdsFlow(): Flow<List<Int>>
}

/** Projection row: checklist totals for one tender. */
data class ChecklistStats(
    val tenderId: Int,
    val total: Int,
    val done: Int
)
