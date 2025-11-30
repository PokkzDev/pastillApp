package com.pokkzdev.pastillapp.database

import android.content.Context
import com.pokkzdev.pastillapp.PillEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date

/**
 * Repository for local pill event storage operations
 */
class PillEventLocalRepository(context: Context) {
    private val dao = PillEventDatabase.getDatabase(context).pillEventDao()

    /**
     * Convert PillEvent to PillEventEntity
     */
    private fun PillEvent.toEntity(userId: String, synced: Boolean = false): PillEventEntity {
        return PillEventEntity(
            id = this.id,
            userId = userId,
            date = this.date,
            pillName = this.pillName,
            amount = this.amount,
            time = this.time,
            dispensed = this.dispensed,
            synced = synced
        )
    }

    /**
     * Convert PillEventEntity to PillEvent
     */
    private fun PillEventEntity.toPillEvent(): PillEvent {
        return PillEvent(
            id = this.id,
            date = this.date,
            pillName = this.pillName,
            amount = this.amount,
            time = this.time,
            dispensed = this.dispensed
        )
    }

    /**
     * Save a pill event locally
     */
    suspend fun saveEvent(userId: String, event: PillEvent, synced: Boolean = false) {
        dao.insertEvent(event.toEntity(userId, synced))
    }

    /**
     * Get all events for a specific date
     */
    suspend fun getEventsForDate(userId: String, date: Date): List<PillEvent> {
        // Normalize date to start of day (midnight)
        val calendar = java.util.Calendar.getInstance()
        calendar.time = date
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = calendar.time.time
        
        // End of day (start of next day)
        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.time.time
        
        return dao.getEventsForDate(userId, startOfDay, endOfDay).map { it.toPillEvent() }
    }

    /**
     * Get all events for a user (as Flow)
     */
    fun getAllEvents(userId: String): Flow<List<PillEvent>> {
        return dao.getAllEvents(userId).map { entities ->
            entities.map { it.toPillEvent() }
        }
    }

    /**
     * Get all unsynced events
     */
    suspend fun getAllUnsyncedEvents(userId: String): List<PillEvent> {
        return dao.getUnsyncedEvents(userId).map { it.toPillEvent() }
    }

    /**
     * Mark an event as synced
     */
    suspend fun markAsSynced(eventId: String) {
        dao.markAsSynced(eventId)
    }

    /**
     * Mark an event as dispensed
     */
    suspend fun markAsDispensed(eventId: String) {
        dao.markAsDispensed(eventId)
    }

    /**
     * Delete an event
     */
    suspend fun deleteEvent(eventId: String) {
        dao.deleteEventById(eventId)
    }

    /**
     * Get event by ID
     */
    suspend fun getEventById(eventId: String): PillEvent? {
        return dao.getEventById(eventId)?.toPillEvent()
    }

    /**
     * Update an event
     */
    suspend fun updateEvent(userId: String, event: PillEvent, synced: Boolean = false) {
        dao.insertEvent(event.toEntity(userId, synced))
    }
}

