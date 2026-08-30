package com.tenderbase.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_tenders")
data class CachedTenderEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val organisation: String?,
    val province: String?,
    val category: String?,
    val closingDate: String?,
    val closingAt: String?,
    val sourceUrl: String?,
    val jsonPayload: String,
    val cachedAt: Long = System.currentTimeMillis()
)
