package com.pokkzdev.pastillapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestor de almacenamiento temporal de datos cuando no hay conexión
 * Cumple con:
 * - 3.1.1.4: Evalúa la capacidad de almacenamiento temporal
 * - 3.1.2.7: Implementa almacenamiento temporal con sincronización
 */
class OfflineDataManager(private val context: Context) {
    
    companion object {
        private const val TAG = "OfflineDataManager"
        private const val OFFLINE_DIR = "offline_data"
        private const val PENDING_COMMANDS_FILE = "pending_commands.json"
        private const val PENDING_EVENTS_FILE = "pending_events.json"
        private const val MAX_STORAGE_MB = 10 // Límite de almacenamiento: 10 MB
        private const val SYNC_INTERVAL_MS = 30000L // Sincronizar cada 30 segundos
    }
    
    private val offlineDir: File by lazy {
        File(context.filesDir, OFFLINE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val pendingCommandsFile: File by lazy {
        File(offlineDir, PENDING_COMMANDS_FILE)
    }
    
    private val pendingEventsFile: File by lazy {
        File(offlineDir, PENDING_EVENTS_FILE)
    }
    
    private var syncJob: Job? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    init {
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
    }
    
    /**
     * Evalúa la capacidad de almacenamiento disponible
     * @return Triple(usado MB, disponible MB, porcentaje usado)
     */
    fun evaluateStorageCapacity(): Triple<Double, Double, Double> {
        val usedBytes = calculateDirectorySize(offlineDir)
        val usedMB = usedBytes / (1024.0 * 1024.0)
        val availableMB = MAX_STORAGE_MB - usedMB
        val percentageUsed = (usedMB / MAX_STORAGE_MB) * 100.0
        
        Log.d(TAG, "Almacenamiento: ${String.format("%.2f", usedMB)} MB usado de $MAX_STORAGE_MB MB (${String.format("%.1f", percentageUsed)}%)")
        
        return Triple(usedMB, availableMB, percentageUsed)
    }
    
    /**
     * Verifica si hay espacio suficiente para almacenar datos
     * @param estimatedSizeKB Tamaño estimado en KB
     * @return true si hay espacio suficiente
     */
    fun hasEnoughSpace(estimatedSizeKB: Double = 10.0): Boolean {
        val (usedMB, _, percentageUsed) = evaluateStorageCapacity()
        val estimatedSizeMB = estimatedSizeKB / 1024.0
        val totalAfterSave = usedMB + estimatedSizeMB
        
        return totalAfterSave < MAX_STORAGE_MB && percentageUsed < 90.0
    }
    
    /**
     * Guarda un comando pendiente para enviar cuando haya conexión
     * @param command Comando a enviar
     * @param priority Prioridad (mayor = más urgente)
     */
    suspend fun savePendingCommand(command: String, priority: Int = 5): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!hasEnoughSpace()) {
                Log.w(TAG, "No hay espacio suficiente para guardar comando")
                return@withContext false
            }
            
            val commands = loadPendingCommands().toMutableList()
            val commandData = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("command", command)
                put("priority", priority)
                put("timestamp", dateFormat.format(Date()))
                put("retryCount", 0)
            }
            
            commands.add(commandData)
            
            // Ordenar por prioridad (mayor primero)
            commands.sortByDescending { it.getInt("priority") }
            
            val jsonArray = JSONArray(commands)
            pendingCommandsFile.writeText(jsonArray.toString())
            
            Log.d(TAG, "Comando guardado offline: $command (prioridad: $priority)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando comando offline", e)
            false
        }
    }
    
    /**
     * Guarda un evento pendiente para sincronizar con Firestore
     */
    suspend fun savePendingEvent(event: PillEvent): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!hasEnoughSpace()) {
                Log.w(TAG, "No hay espacio suficiente para guardar evento")
                return@withContext false
            }
            
            val events = loadPendingEvents().toMutableList()
            val eventData = JSONObject().apply {
                put("id", event.id)
                put("date", dateFormat.format(event.date))
                put("pillName", event.pillName)
                put("amount", event.amount)
                put("time", event.time)
                put("dispensed", event.dispensed)
                put("timestamp", dateFormat.format(Date()))
            }
            
            events.add(eventData)
            
            val jsonArray = JSONArray(events)
            pendingEventsFile.writeText(jsonArray.toString())
            
            Log.d(TAG, "Evento guardado offline: ${event.pillName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando evento offline", e)
            false
        }
    }
    
    /**
     * Obtiene todos los comandos pendientes
     */
    fun loadPendingCommands(): List<JSONObject> {
        return try {
            if (!pendingCommandsFile.exists()) return emptyList()
            
            val jsonArray = JSONArray(pendingCommandsFile.readText())
            List(jsonArray.length()) { i -> jsonArray.getJSONObject(i) }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando comandos pendientes", e)
            emptyList()
        }
    }
    
    /**
     * Obtiene todos los eventos pendientes
     */
    fun loadPendingEvents(): List<JSONObject> {
        return try {
            if (!pendingEventsFile.exists()) return emptyList()
            
            val jsonArray = JSONArray(pendingEventsFile.readText())
            List(jsonArray.length()) { i -> jsonArray.getJSONObject(i) }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando eventos pendientes", e)
            emptyList()
        }
    }
    
    /**
     * Elimina un comando pendiente después de enviarlo exitosamente
     */
    suspend fun removePendingCommand(commandId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val commands = loadPendingCommands().toMutableList()
            commands.removeAll { it.getString("id") == commandId }
            
            val jsonArray = JSONArray(commands)
            pendingCommandsFile.writeText(jsonArray.toString())
            
            Log.d(TAG, "Comando removido: $commandId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removiendo comando", e)
            false
        }
    }
    
    /**
     * Elimina un evento pendiente después de sincronizarlo
     */
    suspend fun removePendingEvent(eventId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val events = loadPendingEvents().toMutableList()
            events.removeAll { it.getString("id") == eventId }
            
            val jsonArray = JSONArray(events)
            pendingEventsFile.writeText(jsonArray.toString())
            
            Log.d(TAG, "Evento removido: $eventId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removiendo evento", e)
            false
        }
    }
    
    /**
     * Incrementa el contador de reintentos de un comando
     */
    suspend fun incrementCommandRetryCount(commandId: String) = withContext(Dispatchers.IO) {
        try {
            val commands = loadPendingCommands().toMutableList()
            val command = commands.find { it.getString("id") == commandId }
            command?.put("retryCount", command.getInt("retryCount") + 1)
            
            val jsonArray = JSONArray(commands)
            pendingCommandsFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error incrementando contador de reintentos", e)
        }
    }
    
    /**
     * Inicia sincronización automática periódica
     */
    fun startAutoSync(
        connectionService: BluetoothConnectionService,
        userId: String,
        scope: CoroutineScope
    ) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                
                if (connectionService.isConnected()) {
                    syncPendingCommands(connectionService)
                }
                
                if (isNetworkAvailable()) {
                    syncPendingEvents(userId)
                }
            }
        }
        Log.d(TAG, "Sincronización automática iniciada")
    }
    
    /**
     * Detiene la sincronización automática
     */
    fun stopAutoSync() {
        syncJob?.cancel()
        syncJob = null
        Log.d(TAG, "Sincronización automática detenida")
    }
    
    /**
     * Sincroniza comandos pendientes con el dispositivo Bluetooth
     */
    suspend fun syncPendingCommands(connectionService: BluetoothConnectionService): Int = withContext(Dispatchers.IO) {
        var syncedCount = 0
        val commands = loadPendingCommands()
        
        Log.d(TAG, "Sincronizando ${commands.size} comandos pendientes")
        
        for (commandData in commands) {
            try {
                val commandId = commandData.getString("id")
                val command = commandData.getString("command")
                val retryCount = commandData.getInt("retryCount")
                
                // Limitar reintentos
                if (retryCount >= 5) {
                    Log.w(TAG, "Comando $commandId excedió límite de reintentos, descartando")
                    removePendingCommand(commandId)
                    continue
                }
                
                connectionService.sendCommand(command)
                delay(500) // Pequeña pausa entre comandos
                
                removePendingCommand(commandId)
                syncedCount++
                Log.d(TAG, "Comando sincronizado: $command")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sincronizando comando", e)
                incrementCommandRetryCount(commandData.getString("id"))
            }
        }
        
        Log.d(TAG, "Sincronización completada: $syncedCount/${commands.size} comandos enviados")
        syncedCount
    }
    
    /**
     * Sincroniza eventos pendientes con Firestore
     */
    suspend fun syncPendingEvents(userId: String): Int = withContext(Dispatchers.IO) {
        var syncedCount = 0
        val events = loadPendingEvents()
        
        Log.d(TAG, "Sincronizando ${events.size} eventos pendientes")
        
        for (eventData in events) {
            try {
                val event = PillEvent(
                    id = eventData.getString("id"),
                    date = dateFormat.parse(eventData.getString("date")) ?: Date(),
                    pillName = eventData.getString("pillName"),
                    amount = eventData.getString("amount"),
                    time = eventData.getString("time"),
                    dispensed = eventData.getBoolean("dispensed")
                )
                
                if (PillEventFirestoreService.addEvent(userId, event)) {
                    removePendingEvent(event.id)
                    syncedCount++
                    Log.d(TAG, "Evento sincronizado: ${event.pillName}")
                } else {
                    Log.w(TAG, "No se pudo sincronizar evento: ${event.id}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sincronizando evento", e)
            }
        }
        
        Log.d(TAG, "Sincronización completada: $syncedCount/${events.size} eventos enviados")
        syncedCount
    }
    
    /**
     * Limpia todos los datos offline
     */
    suspend fun clearAllOfflineData() = withContext(Dispatchers.IO) {
        try {
            pendingCommandsFile.delete()
            pendingEventsFile.delete()
            Log.d(TAG, "Datos offline limpiados")
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando datos offline", e)
        }
    }
    
    /**
     * Obtiene estadísticas del almacenamiento offline
     */
    fun getStorageStats(): OfflineStorageStats {
        val (usedMB, availableMB, percentageUsed) = evaluateStorageCapacity()
        val pendingCommands = loadPendingCommands().size
        val pendingEvents = loadPendingEvents().size
        
        return OfflineStorageStats(
            usedMB = usedMB,
            availableMB = availableMB,
            percentageUsed = percentageUsed,
            pendingCommands = pendingCommands,
            pendingEvents = pendingEvents
        )
    }
    
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }
    
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
                as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Estadísticas del almacenamiento offline
 */
data class OfflineStorageStats(
    val usedMB: Double,
    val availableMB: Double,
    val percentageUsed: Double,
    val pendingCommands: Int,
    val pendingEvents: Int
)
