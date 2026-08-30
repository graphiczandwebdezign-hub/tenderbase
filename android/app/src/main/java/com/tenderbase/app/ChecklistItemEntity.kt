package com.tenderbase.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One bid-preparation checklist item for a tender (local-only). */
@Entity(tableName = "checklist_items")
data class ChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tenderId: Int,
    val label: String,
    val isDone: Boolean = false,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
