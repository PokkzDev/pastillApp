package com.pokkzdev.pastillapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Foreground Service para mantener la conexión Bluetooth activa
 * con el dispositivo ESP32 PastillApp.
 * 
 * Características:
 * - Mantiene la conexión en segundo plano
 * - Auto-reconexión con backoff exponencial mejorado (3.1.1.3)
 * - Heartbeat adaptativo según nivel de batería (3.1.3.10)
 * - Cola de comandos durante reconexión con almacenamiento offline (3.1.2.7)
 * - Verificación de integridad de mensajes con HMAC (3.1.1.2)
 * - Plan de contingencia en caso de fallas (3.1.4.13)
 * - Optimización de consumo energético (3.1.3.10)
 */
class BluetoothConnectionService : Service() {

    companion object {
        private const val TAG = "BTConnectionService"
        private const val CHANNEL_ID = "pastillapp_bluetooth_channel"
        private const val NOTIFICATION_ID = 1001
        
        // UUID estándar para Serial Port Profile (SPP)
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // Tiempos de reconexión (backoff exponencial mejorado)
        private const val INITIAL_RECONNECT_DELAY = 1000L // 1 segundo
        private const val MAX_RECONNECT_DELAY = 60000L // 60 segundos (aumentado)
        private const val MAX_RECONNECT_ATTEMPTS = 10 // Máximo de intentos antes de reporte
        private const val READ_TIMEOUT_MS = 3000L
        
        // Actions
        const val ACTION_CONNECT = "com.pokkzdev.pastillapp.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.pokkzdev.pastillapp.ACTION_DISCONNECT"
        const val ACTION_SEND_COMMAND = "com.pokkzdev.pastillapp.ACTION_SEND_COMMAND"
        const val EXTRA_COMMAND = "command"
        
        // Broadcasts
        const val ACTION_CONNECTION_STATE = "com.pokkzdev.pastillapp.CONNECTION_STATE"
        const val ACTION_DATA_RECEIVED = "com.pokkzdev.pastillapp.DATA_RECEIVED"
        const val EXTRA_IS_CONNECTED = "is_connected"
        const val EXTRA_DATA = "data"
        const val EXTRA_DEVICE_STATUS = "device_status"
        const val EXTRA_LDR_VALUE = "ldr_value"
        const val EXTRA_EVENT = "event"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var device: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private var isConnected = false
    private var shouldReconnect = true
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    private var reconnectAttempts = 0
    
    private var connectionJob: Job? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    
    private val commandQueue = ConcurrentLinkedQueue<String>()
    private val listeners = mutableListOf<BluetoothConnectionListener>()
    
    // Estado actual del dispositivo ESP32
    private var currentDeviceStatus: String = "DESCONOCIDO"
    private var currentLdrValue: Int = 0
    
    // Gestores de nuevas funcionalidades
    private lateinit var offlineDataManager: OfflineDataManager
    private lateinit var powerOptimizationManager: PowerOptimizationManager
    private lateinit var contingencyPlanManager: ContingencyPlanManager
    private var currentHeartbeatInterval = 5000L

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothConnectionService = this@BluetoothConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Inicializar gestores
        offlineDataManager = OfflineDataManager(this)
        powerOptimizationManager = PowerOptimizationManager(this)
        contingencyPlanManager = ContingencyPlanManager(this)
        
        // Iniciar monitoreo de batería para optimización dinámica
        powerOptimizationManager.startBatteryMonitoring(serviceScope) { batteryLevel ->
            // Ajustar intervalo de heartbeat según nivel de batería
            currentHeartbeatInterval = powerOptimizationManager.getOptimalHeartbeatInterval()
            Log.d(TAG, "Heartbeat ajustado a ${currentHeartbeatInterval}ms (batería: $batteryLevel%)")
        }
        
        Log.d(TAG, "Servicio creado con gestores de optimización")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val deviceFromSelected = SelectedDevice.device
                if (deviceFromSelected != null) {
                    connect(deviceFromSelected)
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_SEND_COMMAND -> {
                val command = intent.getStringExtra(EXTRA_COMMAND)
                if (command != null) {
                    sendCommand(command)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldReconnect = false
        disconnect()
        
        // Limpiar gestores
        powerOptimizationManager.cleanup()
        offlineDataManager.stopAutoSync()
        
        serviceScope.cancel()
        Log.d(TAG, "Servicio destruido")
    }

    /**
     * Conecta al dispositivo Bluetooth especificado
     */
    fun connect(bluetoothDevice: BluetoothDevice) {
        if (isConnected && device?.address == bluetoothDevice.address) {
            Log.d(TAG, "Ya conectado a este dispositivo")
            return
        }
        
        device = bluetoothDevice
        shouldReconnect = true
        reconnectDelay = INITIAL_RECONNECT_DELAY
        reconnectAttempts = 0
        
        startForeground(NOTIFICATION_ID, createNotification("Conectando..."))
        startConnection()
    }

    /**
     * Desconecta del dispositivo actual
     */
    fun disconnect() {
        shouldReconnect = false
        connectionJob?.cancel()
        readJob?.cancel()
        heartbeatJob?.cancel()
        
        closeConnection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Envía un comando al dispositivo ESP32
     * El comando se encripta con AES y se añade HMAC para verificación de integridad
     */
    fun sendCommand(command: String) {
        if (isConnected) {
            serviceScope.launch {
                try {
                    // Añadir HMAC para verificación de integridad (3.1.1.2)
                    val commandWithHMAC = MessageIntegrityValidator.attachHMAC(command)
                    if (commandWithHMAC == null) {
                        Log.e(TAG, "Error generando HMAC para comando: $command")
                        contingencyPlanManager.reportFailure(
                            ContingencyPlanManager.FailureType.DATA_INTEGRITY_ERROR,
                            "No se pudo generar HMAC"
                        )
                        return@launch
                    }
                    
                    // Encriptar comando antes de enviar (3.1.1.1)
                    val encryptedCommand = BluetoothEncryption.encrypt(commandWithHMAC)
                    if (encryptedCommand == null) {
                        Log.e(TAG, "Error encriptando comando: $command")
                        contingencyPlanManager.reportFailure(
                            ContingencyPlanManager.FailureType.COMMAND_SEND_FAILED,
                            "Error de encriptación"
                        )
                        return@launch
                    }
                    
                    // Enviar comando encriptado (terminado con \n)
                    val commandBytes = "$encryptedCommand\n".toByteArray()
                    outputStream?.write(commandBytes)
                    outputStream?.flush()
                    Log.d(TAG, "Comando enviado con HMAC y encriptación: ${command.take(20)}...")
                } catch (e: IOException) {
                    Log.e(TAG, "Error enviando comando", e)
                    contingencyPlanManager.reportFailure(
                        ContingencyPlanManager.FailureType.COMMAND_SEND_FAILED,
                        e.message ?: "IOException"
                    )
                    handleConnectionLost()
                }
            }
        } else {
            // Encolar comando y guardar offline para cuando se reconecte (3.1.1.4, 3.1.2.7)
            commandQueue.offer(command)
            serviceScope.launch {
                offlineDataManager.savePendingCommand(command, priority = 7)
            }
            Log.d(TAG, "Comando encolado y guardado offline: $command")
        }
    }

    /**
     * Registra un listener para eventos de conexión
     */
    fun addConnectionListener(listener: BluetoothConnectionListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // Notificar estado actual inmediatamente
            listener.onConnectionStateChanged(isConnected)
            if (isConnected) {
                listener.onDeviceStatusReceived(currentDeviceStatus)
            }
        }
    }

    /**
     * Elimina un listener
     */
    fun removeConnectionListener(listener: BluetoothConnectionListener) {
        listeners.remove(listener)
    }

    /**
     * Verifica si está conectado
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Obtiene el estado actual del dispositivo
     */
    fun getCurrentDeviceStatus(): String = currentDeviceStatus

    /**
     * Obtiene el último valor LDR
     */
    fun getCurrentLdrValue(): Int = currentLdrValue

    private fun startConnection() {
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            while (shouldReconnect && !isConnected) {
                try {
                    reconnectAttempts++
                    Log.d(TAG, "Intento de conexión #$reconnectAttempts a ${device?.name}")
                    
                    // Reportar falla si excede máximo de intentos
                    if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                        contingencyPlanManager.reportFailure(
                            ContingencyPlanManager.FailureType.BLUETOOTH_CONNECTION_LOST,
                            "Excedidos $MAX_RECONNECT_ATTEMPTS intentos de reconexión"
                        )
                        // Resetear contador después de reporte
                        reconnectAttempts = 0
                    }
                    
                    if (!hasBluetoothPermission()) {
                        Log.e(TAG, "Sin permisos de Bluetooth")
                        delay(reconnectDelay)
                        continue
                    }
                    
                    // Verificación de bonding (requisito de seguridad 3.1.4.11)
                    val bondState = device?.bondState
                    if (bondState != BOND_BONDED) {
                        Log.w(TAG, "Dispositivo no está vinculado (bonded). Estado: $bondState")
                        Log.w(TAG, "Iniciando proceso de vinculación...")
                        
                        // Intentar crear bond
                        val bondResult = device?.createBond()
                        if (bondResult == true) {
                            // Esperar a que se complete el bonding
                            var bondWaitCount = 0
                            while (device?.bondState != BOND_BONDED && bondWaitCount < 30) {
                                delay(500)
                                bondWaitCount++
                            }
                            
                            if (device?.bondState != BOND_BONDED) {
                                Log.e(TAG, "No se pudo establecer bonding. Rechazando conexión.")
                                contingencyPlanManager.reportFailure(
                                    ContingencyPlanManager.FailureType.BLUETOOTH_PAIRING_FAILED,
                                    "Timeout esperando bonding"
                                )
                                delay(reconnectDelay)
                                continue
                            }
                            Log.d(TAG, "Bonding completado exitosamente")
                        } else {
                            Log.e(TAG, "No se pudo iniciar el proceso de bonding")
                            contingencyPlanManager.reportFailure(
                                ContingencyPlanManager.FailureType.BLUETOOTH_PAIRING_FAILED,
                                "createBond() falló"
                            )
                            delay(reconnectDelay)
                            continue
                        }
                    }
                    
                    Log.d(TAG, "Dispositivo está vinculado, procediendo con conexión")
                    
                    // Adquirir wake lock para operación crítica (3.1.3.10)
                    powerOptimizationManager.acquireWakeLock()
                    
                    socket = device?.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                    
                    inputStream = socket?.inputStream
                    outputStream = socket?.outputStream
                    
                    isConnected = true
                    reconnectDelay = INITIAL_RECONNECT_DELAY
                    reconnectAttempts = 0 // Resetear contador tras conexión exitosa
                    
                    // Liberar wake lock
                    powerOptimizationManager.releaseWakeLock()
                    
                    Log.d(TAG, "✓ Conexión exitosa")
                    updateNotification("Conectado a ${getDeviceName()}")
                    notifyConnectionState(true)
                    
                    // Procesar comandos encolados y offline (3.1.2.7)
                    processQueuedCommands()
                    
                    // Sincronizar comandos guardados offline
                    serviceScope.launch {
                        offlineDataManager.syncPendingCommands(this@BluetoothConnectionService)
                    }
                    
                    // Iniciar lectura continua
                    startReading()
                    
                    // Iniciar heartbeat adaptativo (3.1.3.10)
                    startHeartbeat()
                    
                } catch (e: IOException) {
                    Log.e(TAG, "Error de conexión, reintentando en ${reconnectDelay}ms", e)
                    contingencyPlanManager.reportFailure(
                        ContingencyPlanManager.FailureType.BLUETOOTH_CONNECTION_LOST,
                        e.message ?: "IOException durante conexión"
                    )
                    closeConnection()
                    powerOptimizationManager.releaseWakeLock()
                    
                    delay(reconnectDelay)
                    // Backoff exponencial mejorado (3.1.1.3)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Error de permisos", e)
                    contingencyPlanManager.reportFailure(
                        ContingencyPlanManager.FailureType.AUTHENTICATION_FAILED,
                        e.message ?: "SecurityException"
                    )
                    powerOptimizationManager.releaseWakeLock()
                    delay(reconnectDelay)
                }
            }
        }
    }

    private fun startReading() {
        readJob?.cancel()
        readJob = serviceScope.launch {
            val buffer = ByteArray(1024)
            val messageBuilder = StringBuilder()
            
            while (isConnected && isActive) {
                try {
                    val available = inputStream?.available() ?: 0
                    if (available > 0) {
                        val bytesRead = inputStream?.read(buffer) ?: 0
                        if (bytesRead > 0) {
                            val data = String(buffer, 0, bytesRead)
                            messageBuilder.append(data)
                            
                            // Procesar líneas completas
                            var newlineIndex: Int
                            while (messageBuilder.indexOf("\n").also { newlineIndex = it } >= 0) {
                                val line = messageBuilder.substring(0, newlineIndex).trim()
                                messageBuilder.delete(0, newlineIndex + 1)
                                
                                if (line.isNotEmpty()) {
                                    processReceivedData(line)
                                }
                            }
                        }
                    }
                    delay(50) // Pequeña pausa para no consumir CPU
                } catch (e: IOException) {
                    if (isConnected) {
                        Log.e(TAG, "Error leyendo datos", e)
                        handleConnectionLost()
                    }
                    break
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isConnected && isActive) {
                // Usar intervalo adaptativo según nivel de batería (3.1.3.10)
                delay(currentHeartbeatInterval)
                
                if (isConnected) {
                    try {
                        // Añadir HMAC al PING para verificación de integridad (3.1.1.2)
                        val pingWithHMAC = MessageIntegrityValidator.attachHMAC("PING")
                        if (pingWithHMAC == null) {
                            Log.e(TAG, "Error generando HMAC para heartbeat")
                            continue
                        }
                        
                        // Encriptar PING antes de enviar (3.1.1.1)
                        val encryptedPing = BluetoothEncryption.encrypt(pingWithHMAC)
                        if (encryptedPing != null) {
                            outputStream?.write("$encryptedPing\n".toByteArray())
                            outputStream?.flush()
                            Log.d(TAG, "Heartbeat enviado (intervalo: ${currentHeartbeatInterval}ms)")
                        } else {
                            Log.e(TAG, "Error encriptando heartbeat")
                            contingencyPlanManager.reportFailure(
                                ContingencyPlanManager.FailureType.COMMAND_SEND_FAILED,
                                "Error encriptando heartbeat"
                            )
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error en heartbeat", e)
                        contingencyPlanManager.reportFailure(
                            ContingencyPlanManager.FailureType.DEVICE_NOT_RESPONDING,
                            "Heartbeat falló: ${e.message}"
                        )
                        handleConnectionLost()
                        break
                    }
                }
            }
        }
    }

    private fun processReceivedData(data: String) {
        Log.d(TAG, "Datos recibidos (raw): $data")
        
        // Intentar desencriptar el mensaje (3.1.1.1)
        var decryptedData = data
        if (BluetoothEncryption.isEncrypted(data)) {
            val decrypted = BluetoothEncryption.decrypt(data)
            if (decrypted != null) {
                decryptedData = decrypted
                Log.d(TAG, "Datos desencriptados: $decryptedData")
                
                // Verificar integridad con HMAC (3.1.1.2)
                val verifiedMessage = MessageIntegrityValidator.verifyAndExtract(decryptedData)
                if (verifiedMessage != null) {
                    decryptedData = verifiedMessage
                    Log.d(TAG, "✓ Integridad verificada correctamente")
                } else {
                    Log.e(TAG, "✗ Integridad comprometida: HMAC inválido")
                    contingencyPlanManager.reportFailure(
                        ContingencyPlanManager.FailureType.DATA_INTEGRITY_ERROR,
                        "HMAC inválido en mensaje recibido"
                    )
                    return // No procesar mensaje comprometido
                }
            } else {
                Log.w(TAG, "No se pudo desencriptar, procesando como texto plano")
                // Intentar procesar como texto plano (compatibilidad hacia atrás)
            }
        }
        
        when {
            // Eventos
            decryptedData.startsWith("EVENT:") -> {
                val event = decryptedData.substringAfter("EVENT:")
                notifyEvent(event)
            }
            
            // Estado del dispositivo
            decryptedData.startsWith("STATUS:") -> {
                currentDeviceStatus = decryptedData.substringAfter("STATUS:")
                notifyDeviceStatus(currentDeviceStatus)
            }
            
            // Valor LDR
            decryptedData.startsWith("LDR=") -> {
                val ldrStr = decryptedData.substringAfter("LDR=")
                currentLdrValue = ldrStr.toIntOrNull() ?: 0
                notifyLdrValue(currentLdrValue)
            }
            
            // UUID
            decryptedData.startsWith("UUID:") -> {
                val uuid = decryptedData.substringAfter("UUID:")
                notifyDataReceived("UUID", uuid)
            }
            
            // PONG (heartbeat response)
            decryptedData == "PONG" -> {
                Log.d(TAG, "Heartbeat OK")
            }
            
            // Otros mensajes
            else -> {
                notifyDataReceived("MESSAGE", decryptedData)
            }
        }
    }

    private fun processQueuedCommands() {
        serviceScope.launch {
            while (commandQueue.isNotEmpty() && isConnected) {
                val command = commandQueue.poll()
                if (command != null) {
                    delay(100) // Pequeña pausa entre comandos
                    sendCommand(command)
                }
            }
        }
    }

    private fun handleConnectionLost() {
        Log.d(TAG, "⚠️ Conexión perdida")
        contingencyPlanManager.reportFailure(
            ContingencyPlanManager.FailureType.BLUETOOTH_CONNECTION_LOST,
            "Conexión perdida inesperadamente"
        )
        closeConnection()
        
        if (shouldReconnect) {
            updateNotification("Reconectando...")
            notifyConnectionState(false)
            
            // Intentar recuperación automática (3.1.4.13)
            serviceScope.launch {
                contingencyPlanManager.attemptRecovery(
                    this@BluetoothConnectionService,
                    offlineDataManager
                )
            }
            
            startConnection()
        }
    }

    private fun closeConnection() {
        isConnected = false
        currentDeviceStatus = "DESCONOCIDO"
        
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error cerrando conexión", e)
        }
        
        inputStream = null
        outputStream = null
        socket = null
    }

    private fun notifyConnectionState(connected: Boolean) {
        listeners.forEach { it.onConnectionStateChanged(connected) }
        
        // Broadcast para otros componentes
        val intent = Intent(ACTION_CONNECTION_STATE).apply {
            putExtra(EXTRA_IS_CONNECTED, connected)
        }
        sendBroadcast(intent)
    }

    private fun notifyDeviceStatus(status: String) {
        listeners.forEach { it.onDeviceStatusReceived(status) }
        
        val intent = Intent(ACTION_DATA_RECEIVED).apply {
            putExtra(EXTRA_DEVICE_STATUS, status)
        }
        sendBroadcast(intent)
    }

    private fun notifyLdrValue(value: Int) {
        listeners.forEach { it.onLdrValueReceived(value) }
        
        val intent = Intent(ACTION_DATA_RECEIVED).apply {
            putExtra(EXTRA_LDR_VALUE, value)
        }
        sendBroadcast(intent)
    }

    private fun notifyEvent(event: String) {
        listeners.forEach { it.onEventReceived(event) }
        
        val intent = Intent(ACTION_DATA_RECEIVED).apply {
            putExtra(EXTRA_EVENT, event)
        }
        sendBroadcast(intent)
    }

    private fun notifyDataReceived(type: String, data: String) {
        listeners.forEach { it.onDataReceived(type, data) }
        
        val intent = Intent(ACTION_DATA_RECEIVED).apply {
            putExtra(EXTRA_DATA, "$type:$data")
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PastillApp Bluetooth",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Conexión Bluetooth con el dispensador"
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
            .setContentTitle("PastillApp")
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

    private fun hasBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getDeviceName(): String {
        return try {
            if (hasBluetoothPermission()) {
                device?.name ?: "Dispositivo"
            } else {
                "Dispositivo"
            }
        } catch (e: SecurityException) {
            "Dispositivo"
        }
    }
}

/**
 * Interface para escuchar eventos de conexión Bluetooth
 */
interface BluetoothConnectionListener {
    fun onConnectionStateChanged(isConnected: Boolean)
    fun onDeviceStatusReceived(status: String)
    fun onLdrValueReceived(value: Int)
    fun onEventReceived(event: String)
    fun onDataReceived(type: String, data: String)
}

