package com.pokkzdev.pastillapp.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

/**
 * Data Access Object for pill events
 */
@Dao
interface PillEventDao {
    @Query("SELECT * FROM pill_events WHERE userId = :userId ORDER BY date ASC, time ASC")
    fun getAllEvents(userId: String): Flow<List<PillEventEntity>>

    @Query("SELECT * FROM pill_events WHERE userId = :userId AND date >= :startOfDay AND date < :endOfDay ORDER BY time ASC")
    suspend fun getEventsForDate(userId: String, startOfDay: Long, endOfDay: Long): List<PillEventEntity>

    @Query("SELECT * FROM pill_events WHERE userId = :userId AND synced = 0")
    suspend fun getUnsyncedEvents(userId: String): List<PillEventEntity>

    @Query("SELECT * FROM pill_events WHERE id = :eventId")
    suspend fun getEventById(eventId: String): PillEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: PillEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<PillEventEntity>)

    @Update
    suspend fun updateEvent(event: PillEventEntity)

    @Query("UPDATE pill_events SET synced = 1 WHERE id = :eventId")
    suspend fun markAsSynced(eventId: String)

    @Query("UPDATE pill_events SET dispensed = 1 WHERE id = :eventId")
    suspend fun markAsDispensed(eventId: String)

    @Delete
    suspend fun deleteEvent(event: PillEventEntity)

    @Query("DELETE FROM pill_events WHERE id = :eventId")
    suspend fun deleteEventById(eventId: String)

    @Query("DELETE FROM pill_events WHERE userId = :userId")
    suspend fun deleteAllEventsForUser(userId: String)
}

