package com.pokkzdev.pastillapp

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

object PillEventFirestoreService {
    private const val TAG = "PillEventFirestoreService"
    private val db = FirebaseFirestore.getInstance()
    private const val PILL_EVENTS_COLLECTION = "pillEvents"
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * Add a pill event to Firestore
     * @param userId Firebase Auth user ID
     * @param event PillEvent to add
     * @return true if added successfully, false otherwise
     */
    suspend fun addEvent(userId: String, event: PillEvent): Boolean {
        return try {
            Log.d(TAG, "Adding event: userId=$userId, eventId=${event.id}, pillName=${event.pillName}")
            
            // Validate parameters
            if (userId.isBlank()) {
                Log.e(TAG, "UserId is empty")
                return false
            }
            if (event.pillName.isBlank()) {
                Log.e(TAG, "Pill name is empty")
                return false
            }

            // Normalize date to start of day (midnight) for consistent date comparison
            val calendar = Calendar.getInstance()
            calendar.time = event.date
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val normalizedDate = calendar.time
            
            // Convert Date to Firestore Timestamp
            val dateTimestamp = Timestamp(normalizedDate)
            
            Log.d(TAG, "Normalized date: ${dateKeyFormat.format(normalizedDate)}")

            // Create event data map
            val eventData = hashMapOf(
                "id" to event.id,
                "userId" to userId,
                "date" to dateTimestamp,
                "pillName" to event.pillName,
                "amount" to event.amount,
                "time" to event.time,
                "dispensed" to event.dispensed,
                "createdAt" to FieldValue.serverTimestamp()
            )

            // Save to Firestore using event ID as document ID
            db.collection(PILL_EVENTS_COLLECTION)
                .document(event.id)
                .set(eventData)
                .await()

            Log.d(TAG, "Event added successfully: ${event.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding event to Firestore", e)
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Delete a pill event from Firestore
     * @param eventId ID of the event to delete
     * @return true if deleted successfully, false otherwise
     */
    suspend fun deleteEvent(eventId: String): Boolean {
        return try {
            Log.d(TAG, "Deleting event: eventId=$eventId")
            
            if (eventId.isBlank()) {
                Log.e(TAG, "EventId is empty")
                return false
            }

            db.collection(PILL_EVENTS_COLLECTION)
                .document(eventId)
                .delete()
                .await()

            Log.d(TAG, "Event deleted successfully: $eventId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting event from Firestore", e)
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Get all events for a specific date
     * @param userId Firebase Auth user ID
     * @param date Date to query events for
     * @return List of PillEvent objects for the specified date
     */
    suspend fun getEventsForDate(userId: String, date: Date): List<PillEvent> {
        return try {
            Log.d(TAG, "Getting events for date: userId=$userId, date=${dateKeyFormat.format(date)}")
            
            if (userId.isBlank()) {
                Log.e(TAG, "UserId is empty")
                return emptyList()
            }

            // Get target date string for comparison
            val targetDateKey = dateKeyFormat.format(date)
            Log.d(TAG, "Target date key: $targetDateKey")

            // Query Firestore for all user events (simpler query, filter client-side)
            // This avoids needing a composite index and works with security rules
            Log.d(TAG, "Querying all events for user...")
            val querySnapshot = db.collection(PILL_EVENTS_COLLECTION)
                .whereEqualTo("userId", userId)
                .get()
                .await()

            Log.d(TAG, "Query returned ${querySnapshot.documents.size} total documents for user")
            
            val events = mutableListOf<PillEvent>()
            for (document in querySnapshot.documents) {
                try {
                    val event = documentToPillEvent(document)
                    if (event != null) {
                        // Filter by date on client side
                        val eventDateKey = dateKeyFormat.format(event.date)
                        Log.d(TAG, "Event ${event.pillName} has date: $eventDateKey")
                        
                        if (eventDateKey == targetDateKey) {
                            events.add(event)
                            Log.d(TAG, "Matched event: ${event.pillName}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing document ${document.id}", e)
                }
            }

            // Sort by time
            val sortedEvents = events.sortedBy { it.time }

            Log.d(TAG, "Found ${sortedEvents.size} events for date ${dateKeyFormat.format(date)}")
            sortedEvents
        } catch (e: Exception) {
            Log.e(TAG, "Error getting events for date from Firestore", e)
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Get all events for a user
     * @param userId Firebase Auth user ID
     * @return List of all PillEvent objects for the user
     */
    suspend fun getAllEvents(userId: String): List<PillEvent> {
        return try {
            Log.d(TAG, "Getting all events for user: userId=$userId")
            
            if (userId.isBlank()) {
                Log.e(TAG, "UserId is empty")
                return emptyList()
            }

            // Query Firestore for all user events
            val querySnapshot = db.collection(PILL_EVENTS_COLLECTION)
                .whereEqualTo("userId", userId)
                .get()
                .await()

            val events = mutableListOf<PillEvent>()
            for (document in querySnapshot.documents) {
                try {
                    val event = documentToPillEvent(document)
                    if (event != null) {
                        events.add(event)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing document ${document.id}", e)
                }
            }

            Log.d(TAG, "Found ${events.size} total events for user")
            events
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all events from Firestore", e)
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Mark a pill event as dispensed in Firestore
     * @param eventId ID of the event to mark as dispensed
     * @return true if updated successfully, false otherwise
     */
    suspend fun markEventAsDispensed(eventId: String): Boolean {
        return try {
            Log.d(TAG, "Marking event as dispensed: eventId=$eventId")
            
            if (eventId.isBlank()) {
                Log.e(TAG, "EventId is empty")
                return false
            }

            db.collection(PILL_EVENTS_COLLECTION)
                .document(eventId)
                .update("dispensed", true)
                .await()

            Log.d(TAG, "Event marked as dispensed successfully: $eventId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error marking event as dispensed", e)
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Convert Firestore document to PillEvent
     */
    private fun documentToPillEvent(document: com.google.firebase.firestore.DocumentSnapshot): PillEvent? {
        return try {
            val id = document.getString("id") ?: document.id
            val dateTimestamp = document.getTimestamp("date")
            val pillName = document.getString("pillName") ?: ""
            val amount = document.getString("amount") ?: ""
            val time = document.getString("time") ?: ""
            val dispensed = document.getBoolean("dispensed") ?: false

            if (dateTimestamp == null) {
                Log.e(TAG, "Date timestamp is null for document ${document.id}")
                return null
            }

            // Convert Firestore Timestamp to Date
            val date = dateTimestamp.toDate()

            PillEvent(
                id = id,
                date = date,
                pillName = pillName,
                amount = amount,
                time = time,
                dispensed = dispensed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error converting document to PillEvent", e)
            null
        }
    }
}

