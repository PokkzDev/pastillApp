package com.pokkzdev.pastillapp

import java.text.SimpleDateFormat
import java.util.*

/**
 * Global singleton object to manage pill events across the application.
 * Events are stored in memory and persist while the app is running.
 * 
 * Usage example:
 * ```
 * // Add an event
 * val event = PillEvent(date = Date(), pillName = "Aspirina", amount = "1 tableta")
 * PillEventManager.addEvent(event)
 * 
 * // Get events for today
 * val todayEvents = PillEventManager.getEventsForDate(Date())
 * 
 * // Get all events
 * val allEvents = PillEventManager.getAllEvents()
 * 
 * // Check if a date has events
 * if (PillEventManager.hasEventsForDate(someDate)) {
 *     // Do something
 * }
 * ```
 */
object PillEventManager {
    
    private val events = mutableListOf<PillEvent>()
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    /**
     * Add a new pill event
     */
    fun addEvent(event: PillEvent) {
        events.add(event)
    }
    
    /**
     * Remove a pill event
     */
    fun removeEvent(event: PillEvent): Boolean {
        return events.remove(event)
    }
    
    /**
     * Get all events for a specific date
     */
    fun getEventsForDate(date: Date): List<PillEvent> {
        val dateKey = dateKeyFormat.format(date)
        return events.filter { 
            dateKeyFormat.format(it.date) == dateKey 
        }.sortedBy { it.time }
    }
    
    /**
     * Get all events
     */
    fun getAllEvents(): List<PillEvent> {
        return events.toList()
    }
    
    /**
     * Clear all events
     */
    fun clearAllEvents() {
        events.clear()
    }
    
    /**
     * Get count of events for a specific date
     */
    fun getEventCountForDate(date: Date): Int {
        val dateKey = dateKeyFormat.format(date)
        return events.count { 
            dateKeyFormat.format(it.date) == dateKey 
        }
    }
    
    /**
     * Check if there are any events for a specific date
     */
    fun hasEventsForDate(date: Date): Boolean {
        return getEventCountForDate(date) > 0
    }
    
    /**
     * Get all unique dates that have events
     */
    fun getDatesWithEvents(): List<Date> {
        return events.map { it.date }
            .distinctBy { dateKeyFormat.format(it) }
            .sortedBy { it.time }
    }
}
