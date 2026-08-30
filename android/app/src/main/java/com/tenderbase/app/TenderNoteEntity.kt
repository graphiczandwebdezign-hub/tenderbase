package com.tenderbase.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One workspace note per tender (local-only bid prep note). */
@Entity(tableName = "tender_notes")
data class TenderNoteEntity(
    @PrimaryKey val tenderId: Int,
    val note: String,
    val updatedAt: Long = System.currentTimeMillis()
)
