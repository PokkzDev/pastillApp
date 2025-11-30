package com.pokkzdev.pastillapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground Service that automatically polls Firestore every 30 seconds
 * to check for pill events that need to be dispensed.
 * 
 * When a pill event's scheduled time has passed and it hasn't been dispensed yet,
 * this service will send the PASTILLA command to the ESP32 to open the compartment.
 */
class PillDispensingService : Service() {

    companion object {
        private const val TAG = "PillDispensingService"
        private const val CHANNEL_ID = "pastillapp_dispensing_channel"
        private const val NOTIFICATION_ID = 1002
        private const val POLLING_INTERVAL_MS = 30000L // 30 seconds
        private const val TIME_FORMAT = "HH:mm"
        
        // Actions
        const val ACTION_START = "com.pokkzdev.pastillapp.ACTION_START_DISPENSING"
        const val ACTION_STOP = "com.pokkzdev.pastillapp.ACTION_STOP_DISPENSING"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeFormat = SimpleDateFormat(TIME_FORMAT, Locale.getDefault())
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    private var pollingJob: Job? = null
    private var isPolling = false
    private var bluetoothService: BluetoothConnectionService? = null

    inner class LocalBinder : Binder() {
        fun getService(): PillDispensingService = this@PillDispensingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Set up callback to send events to ESP32 when added offline
        PillEventFirestoreService.onOfflineEventAdded = { event ->
            if (bluetoothService?.isConnected() == true && !NetworkUtils.isNetworkAvailable(this)) {
                Log.d(TAG, "New event added offline, sending to ESP32: ${event.id}")
                bluetoothService?.sendPillEventsToESP32(listOf(event))
            }
        }
        
        Log.d(TAG, "PillDispensingService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startPolling()
            }
            ACTION_STOP -> {
                stopPolling()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        serviceScope.cancel()
        
        // Clear callback
        PillEventFirestoreService.onOfflineEventAdded = null
        
        Log.d(TAG, "PillDispensingService destroyed")
    }

    /**
     * Sets the BluetoothConnectionService instance for checking ESP32 status
     */
    fun setBluetoothService(service: BluetoothConnectionService?) {
        bluetoothService = service
        Log.d(TAG, "Bluetooth service ${if (service != null) "set" else "cleared"}")
    }

    /**
     * Starts polling Firestore for pill events
     */
    private fun startPolling() {
        if (isPolling) {
            Log.d(TAG, "Polling already started")
            return
        }
        
        isPolling = true
        startForeground(NOTIFICATION_ID, createNotification("Monitoreando eventos de pastillas..."))
        
        pollingJob = serviceScope.launch {
            while (isPolling && isActive) {
                try {
                    checkAndDispensePills()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during polling cycle", e)
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
        
        Log.d(TAG, "Polling started")
    }

    /**
     * Stops polling
     */
    private fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "Polling stopped")
    }

    /**
     * Checks Firestore or local storage for today's pill events and dispenses if conditions are met
     */
    private suspend fun checkAndDispensePills() {
        val auth = FirebaseAuthClient.auth
        val currentUser = auth.currentUser
        
        if (currentUser == null) {
            Log.d(TAG, "No user logged in, skipping check")
            return
        }
        
        val userId = currentUser.uid
        val today = Date()
        
        Log.d(TAG, "Checking for pill events for user: $userId")
        
        // Check network connectivity
        val isOnline = NetworkUtils.isNetworkAvailable(this)
        Log.d(TAG, "Network status: ${if (isOnline) "online" else "offline"}")
        
        // Get today's events from Firestore or local storage
        val events = PillEventFirestoreService.getEventsForDate(userId, today, this)
        
        // If online, try to sync local events to Firebase
        if (isOnline) {
            try {
                val syncedCount = PillEventFirestoreService.syncLocalEventsToFirebase(userId, this)
                if (syncedCount > 0) {
                    Log.d(TAG, "Synced $syncedCount events to Firebase")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing events to Firebase", e)
            }
        } else {
            // If offline and connected to ESP32, send local events to ESP32
            if (bluetoothService?.isConnected() == true) {
                try {
                    val localRepo = com.pokkzdev.pastillapp.database.PillEventLocalRepository(this)
                    val allLocalEvents = localRepo.getAllUnsyncedEvents(userId)
                    if (allLocalEvents.isNotEmpty()) {
                        Log.d(TAG, "Sending ${allLocalEvents.size} local events to ESP32 (offline mode)")
                        bluetoothService?.sendPillEventsToESP32(allLocalEvents)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending events to ESP32", e)
                }
            }
        }
        
        if (events.isEmpty()) {
            Log.d(TAG, "No events found for today")
            return
        }
        
        Log.d(TAG, "Found ${events.size} events for today")
        
        // Filter events that haven't been dispensed
        val undispensedEvents = events.filter { !it.dispensed }
        
        if (undispensedEvents.isEmpty()) {
            Log.d(TAG, "All events for today have been dispensed")
            return
        }
        
        Log.d(TAG, "Found ${undispensedEvents.size} undispensed events")
        
        // Get current time
        val currentTime = Calendar.getInstance()
        val currentTimeString = timeFormat.format(currentTime.time)
        val currentTimeMinutes = timeToMinutes(currentTimeString)
        
        // Check each undispensed event
        for (event in undispensedEvents) {
            if (event.time.isBlank()) {
                Log.d(TAG, "Event ${event.id} has no time specified, skipping")
                continue
            }
            
            val eventTimeMinutes = timeToMinutes(event.time)
            
            // Skip if time parsing failed
            if (eventTimeMinutes < 0) {
                Log.d(TAG, "Event ${event.id} has invalid time format: ${event.time}")
                continue
            }
            
            // Check if event time has passed (current time >= event time)
            if (currentTimeMinutes >= eventTimeMinutes) {
                Log.d(TAG, "Event ${event.id} (${event.pillName}) scheduled for ${event.time} - time has passed")
                
                // Check if ESP32 is connected and in CERRADO state
                if (shouldDispense(event)) {
                    Log.d(TAG, "Dispensing pill for event ${event.id}: ${event.pillName}")
                    dispatchPill(event)
                } else {
                    Log.d(TAG, "Cannot dispense event ${event.id}: ESP32 not ready")
                }
            } else {
                Log.d(TAG, "Event ${event.id} (${event.pillName}) scheduled for ${event.time} - time has not passed yet")
            }
        }
    }

    /**
     * Checks if conditions are met to dispense a pill
     */
    private fun shouldDispense(event: PillEvent): Boolean {
        // Check if Bluetooth service is available
        if (bluetoothService == null) {
            Log.d(TAG, "Bluetooth service not available")
            return false
        }
        
        // Check if ESP32 is connected
        if (!bluetoothService!!.isConnected()) {
            Log.d(TAG, "ESP32 not connected")
            return false
        }
        
        // Check if ESP32 is in CERRADO state
        val status = bluetoothService!!.getCurrentDeviceStatus()
        if (status != "CERRADO") {
            Log.d(TAG, "ESP32 is not in CERRADO state (current: $status)")
            return false
        }
        
        return true
    }

    /**
     * Sends PASTILLA command to ESP32 and marks event as dispensed
     */
    private suspend fun dispatchPill(event: PillEvent) {
        try {
            // Send PASTILLA command to ESP32
            bluetoothService?.sendCommand("PASTILLA")
            Log.d(TAG, "PASTILLA command sent for event ${event.id}")
            
            // Wait a moment to ensure command was sent
            delay(500)
            
            // Mark event as dispensed in Firestore and local storage
            val success = PillEventFirestoreService.markEventAsDispensed(event.id, this)
            
            if (success) {
                Log.d(TAG, "Event ${event.id} marked as dispensed in Firestore")
                updateNotification("Pastilla dispensada: ${event.pillName}")
            } else {
                Log.e(TAG, "Failed to mark event ${event.id} as dispensed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispensing pill for event ${event.id}", e)
        }
    }

    /**
     * Converts time string (HH:mm) to minutes since midnight
     */
    private fun timeToMinutes(timeString: String): Int {
        return try {
            val parts = timeString.split(":")
            if (parts.size != 2) {
                Log.e(TAG, "Invalid time format: $timeString")
                return -1
            }
            val hours = parts[0].toInt()
            val minutes = parts[1].toInt()
            hours * 60 + minutes
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing time: $timeString", e)
            -1
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PastillApp Dispensación Automática",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio de dispensación automática de pastillas"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PastillApp - Dispensación Automática")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}

