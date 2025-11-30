package com.pokkzdev.pastillapp.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.util.Date

/**
 * Entity class for local storage of pill events using Room database
 */
@Entity(tableName = "pill_events")
@TypeConverters(DateConverters::class)
data class PillEventEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val date: Date,
    val pillName: String,
    val amount: String,
    val time: String,
    val dispensed: Boolean,
    val synced: Boolean = false
)


