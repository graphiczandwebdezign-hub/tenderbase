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
}
