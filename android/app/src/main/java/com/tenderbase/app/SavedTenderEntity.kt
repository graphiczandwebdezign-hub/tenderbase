package com.tenderbase.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_tenders")
data class SavedTenderEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val organisation: String?,
    val province: String?,
    val category: String?,
    val closingDate: String?,
    val closingAt: String?,
    val sourceUrl: String?,
    val savedAt: Long = System.currentTimeMillis()
) {
    fun toTender(): Tender = Tender(
        id = id,
        title = title,
        description = null,
        organisation = organisation,
        province = province,
        category = category,
        categories = category?.let { listOf(it) } ?: emptyList(),
        tenderType = null,
        status = null,
        closingDate = closingDate,
        closingAt = closingAt,
        sourceUrl = sourceUrl,
        documents = emptyList()
    )

    companion object {
        fun fromTender(t: Tender): SavedTenderEntity = SavedTenderEntity(
            id = t.id,
            title = t.title,
            organisation = t.organisation,
            province = t.province,
            category = t.category ?: t.categories.firstOrNull(),
            closingDate = t.closingDate,
            closingAt = t.closingAt,
            sourceUrl = t.sourceUrl
        )
    }
}
