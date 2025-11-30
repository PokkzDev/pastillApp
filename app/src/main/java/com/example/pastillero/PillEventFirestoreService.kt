package com.pokkzdev.pastillapp

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.pokkzdev.pastillapp.database.PillEventLocalRepository
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.*

object PillEventFirestoreService {
    private const val TAG = "PillEventFirestoreService"
    private val db = FirebaseFirestore.getInstance()
    private const val PILL_EVENTS_COLLECTION = "pillEvents"
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    // Local repository cache (lazy initialization)
    private var localRepository: PillEventLocalRepository? = null
    
    // Callback for when events are added offline (to notify ESP32)
    var onOfflineEventAdded: ((PillEvent) -> Unit)? = null
    
    /**
     * Initialize local repository with context
     * Should be called from Application or MainActivity
     */
    fun initialize(context: Context) {
        if (localRepository == null) {
            localRepository = PillEventLocalRepository(context)
        }
    }
    
    private fun getLocalRepository(context: Context?): PillEventLocalRepository? {
        return context?.let { 
            if (localRepository == null) {
                localRepository = PillEventLocalRepository(it)
            }
            localRepository
        }
    }

    /**
     * Add a pill event to Firestore (with offline fallback)
     * @param userId Firebase Auth user ID
     * @param event PillEvent to add
     * @param context Optional context for local storage fallback
     * @return true if added successfully (to Firebase or local storage), false otherwise
     */
    suspend fun addEvent(userId: String, event: PillEvent, context: Context? = null): Boolean {
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
        
        Log.d(TAG, "Normalized date: ${dateKeyFormat.format(normalizedDate)}")

        // Check network availability BEFORE attempting Firebase
        val isOnline = context?.let { NetworkUtils.isNetworkAvailable(it) } ?: false
        Log.d(TAG, "Network status: ${if (isOnline) "online" else "offline"}")

        // If offline, skip Firebase and save directly to local storage
        if (!isOnline) {
            Log.d(TAG, "Device is offline, saving directly to local storage")
            if (context != null) {
                return try {
                    val localRepo = getLocalRepository(context)
                    if (localRepo != null) {
                        localRepo.saveEvent(userId, event, synced = false)
                        Log.d(TAG, "Event saved to local storage (offline mode, will sync later): ${event.id}")
                        
                        // Notify that an event was added offline (so it can be sent to ESP32)
                        onOfflineEventAdded?.invoke(event)
                        
                        true
                    } else {
                        Log.e(TAG, "Local repository not available")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving event to local storage", e)
                    e.printStackTrace()
                    false
                }
            } else {
                Log.e(TAG, "Cannot save event: offline and no context provided for local storage")
                return false
            }
        }

        // Online: Try Firebase first with timeout
        val firebaseSuccess = try {
            // Convert Date to Firestore Timestamp
            val dateTimestamp = Timestamp(normalizedDate)

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
            // Use withTimeout to prevent hanging
            kotlinx.coroutines.withTimeout(10000) { // 10 second timeout
                db.collection(PILL_EVENTS_COLLECTION)
                    .document(event.id)
                    .set(eventData)
                    .await()
            }

            Log.d(TAG, "Event added successfully to Firebase: ${event.id}")
            true
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Firebase operation timed out, will try local storage", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error adding event to Firestore, will try local storage", e)
            Log.w(TAG, "Error type: ${e.javaClass.simpleName}, message: ${e.message}")
            false
        }

        // If Firebase succeeded, also save to local storage for offline access
        if (firebaseSuccess) {
            context?.let { ctx ->
                try {
                    getLocalRepository(ctx)?.saveEvent(userId, event, synced = true)
                    Log.d(TAG, "Event also saved to local storage: ${event.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving to local storage", e)
                }
            }
            return true
        }

        // Firebase failed or timed out, try local storage if context is available
        if (context != null) {
            return try {
                val localRepo = getLocalRepository(context)
                if (localRepo != null) {
                    localRepo.saveEvent(userId, event, synced = false)
                    Log.d(TAG, "Event saved to local storage (Firebase unavailable, will sync later): ${event.id}")
                    true
                } else {
                    Log.e(TAG, "Local repository not available")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving event to local storage", e)
                e.printStackTrace()
                false
            }
        }

        // Both Firebase and local storage failed (or no context)
        Log.e(TAG, "Failed to save event: Firebase failed/timed out and no local storage available")
        return false
    }

    /**
     * Delete a pill event from Firestore (and local storage)
     * @param eventId ID of the event to delete
     * @param context Optional context for local storage deletion
     * @return true if deleted successfully, false otherwise
     */
    suspend fun deleteEvent(eventId: String, context: Context? = null): Boolean {
        Log.d(TAG, "Deleting event: eventId=$eventId")
        
        if (eventId.isBlank()) {
            Log.e(TAG, "EventId is empty")
            return false
        }

        // Try Firebase first
        val firebaseSuccess = try {
            db.collection(PILL_EVENTS_COLLECTION)
                .document(eventId)
                .delete()
                .await()
            Log.d(TAG, "Event deleted successfully from Firebase: $eventId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting event from Firestore", e)
            false
        }

        // Also delete from local storage
        context?.let { ctx ->
            try {
                getLocalRepository(ctx)?.deleteEvent(eventId)
                Log.d(TAG, "Event deleted from local storage: $eventId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting from local storage", e)
            }
        }

        // Return true if Firebase succeeded, or if we at least tried local deletion
        return firebaseSuccess || context != null
    }

    /**
     * Get all events for a specific date (from Firebase or local storage)
     * @param userId Firebase Auth user ID
     * @param date Date to query events for
     * @param context Optional context for local storage fallback
     * @return List of PillEvent objects for the specified date
     */
    suspend fun getEventsForDate(userId: String, date: Date, context: Context? = null): List<PillEvent> {
        Log.d(TAG, "Getting events for date: userId=$userId, date=${dateKeyFormat.format(date)}")
        
        if (userId.isBlank()) {
            Log.e(TAG, "UserId is empty")
            return emptyList()
        }

        // Get target date string for comparison
        val targetDateKey = dateKeyFormat.format(date)
        Log.d(TAG, "Target date key: $targetDateKey")

        // Check network availability BEFORE attempting Firebase
        val isOnline = context?.let { NetworkUtils.isNetworkAvailable(it) } ?: false
        Log.d(TAG, "Network status: ${if (isOnline) "online" else "offline"}")

        // If offline, skip Firebase and get directly from local storage
        if (!isOnline) {
            Log.d(TAG, "Device is offline, getting events from local storage")
            if (context != null) {
                return try {
                    val localRepo = getLocalRepository(context)
                    if (localRepo != null) {
                        val localEvents = localRepo.getEventsForDate(userId, date)
                        Log.d(TAG, "Found ${localEvents.size} events from local storage for date ${dateKeyFormat.format(date)}")
                        localEvents
                    } else {
                        Log.e(TAG, "Local repository not available")
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting events from local storage", e)
                    emptyList()
                }
            } else {
                Log.e(TAG, "Cannot get events: offline and no context provided for local storage")
                return emptyList()
            }
        }

        // Online: Try Firebase first with timeout
        val firebaseEvents = try {
            // Query Firestore for all user events (simpler query, filter client-side)
            // This avoids needing a composite index and works with security rules
            Log.d(TAG, "Querying all events for user from Firebase...")
            
            val querySnapshot = kotlinx.coroutines.withTimeout(10000) { // 10 second timeout
                db.collection(PILL_EVENTS_COLLECTION)
                    .whereEqualTo("userId", userId)
                    .get()
                    .await()
            }

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
            events.sortedBy { it.time }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Firebase query timed out, will try local storage", e)
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting events for date from Firestore, will try local storage", e)
            emptyList()
        }

        // If Firebase succeeded, return those events
        if (firebaseEvents.isNotEmpty()) {
            Log.d(TAG, "Found ${firebaseEvents.size} events from Firebase for date ${dateKeyFormat.format(date)}")
            return firebaseEvents
        }

        // Firebase failed or timed out, try local storage if context is available
        if (context != null) {
            return try {
                val localRepo = getLocalRepository(context)
                if (localRepo != null) {
                    val localEvents = localRepo.getEventsForDate(userId, date)
                    Log.d(TAG, "Found ${localEvents.size} events from local storage for date ${dateKeyFormat.format(date)}")
                    localEvents
                } else {
                    Log.e(TAG, "Local repository not available")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting events from local storage", e)
                emptyList()
            }
        }

        // No context available, return empty list
        Log.d(TAG, "No events found (Firebase failed and no local storage available)")
        return emptyList()
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
     * Mark a pill event as dispensed in Firestore (and local storage)
     * @param eventId ID of the event to mark as dispensed
     * @param context Optional context for local storage update
     * @return true if updated successfully, false otherwise
     */
    suspend fun markEventAsDispensed(eventId: String, context: Context? = null): Boolean {
        Log.d(TAG, "Marking event as dispensed: eventId=$eventId")
        
        if (eventId.isBlank()) {
            Log.e(TAG, "EventId is empty")
            return false
        }

        // Try Firebase first
        val firebaseSuccess = try {
            db.collection(PILL_EVENTS_COLLECTION)
                .document(eventId)
                .update("dispensed", true)
                .await()
            Log.d(TAG, "Event marked as dispensed in Firebase: $eventId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error marking event as dispensed in Firestore", e)
            false
        }

        // Also update local storage
        context?.let { ctx ->
            try {
                getLocalRepository(ctx)?.markAsDispensed(eventId)
                Log.d(TAG, "Event marked as dispensed in local storage: $eventId")
            } catch (e: Exception) {
                Log.e(TAG, "Error marking event as dispensed in local storage", e)
            }
        }

        // Return true if Firebase succeeded, or if we at least tried local update
        return firebaseSuccess || context != null
    }
    
    /**
     * Sync local unsynced events to Firebase
     * @param userId Firebase Auth user ID
     * @param context Context for accessing local storage
     * @return Number of events successfully synced
     */
    suspend fun syncLocalEventsToFirebase(userId: String, context: Context): Int {
        Log.d(TAG, "Syncing local events to Firebase for user: $userId")
        
        if (userId.isBlank()) {
            Log.e(TAG, "UserId is empty")
            return 0
        }

        val localRepo = getLocalRepository(context) ?: run {
            Log.e(TAG, "Local repository not available")
            return 0
        }

        return try {
            val unsyncedEvents = localRepo.getAllUnsyncedEvents(userId)
            Log.d(TAG, "Found ${unsyncedEvents.size} unsynced events")

            var syncedCount = 0
            for (event in unsyncedEvents) {
                try {
                    // Normalize date
                    val calendar = Calendar.getInstance()
                    calendar.time = event.date
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val normalizedDate = calendar.time
                    val dateTimestamp = Timestamp(normalizedDate)

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

                    // Save to Firestore
                    db.collection(PILL_EVENTS_COLLECTION)
                        .document(event.id)
                        .set(eventData)
                        .await()

                    // Mark as synced
                    localRepo.markAsSynced(event.id)
                    syncedCount++
                    Log.d(TAG, "Synced event: ${event.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing event ${event.id}", e)
                }
            }

            Log.d(TAG, "Successfully synced $syncedCount out of ${unsyncedEvents.size} events")
            syncedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing local events to Firebase", e)
            0
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

