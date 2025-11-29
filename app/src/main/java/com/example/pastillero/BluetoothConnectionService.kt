package com.pokkzdev.pastillapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
 * - Auto-reconexión con backoff exponencial
 * - Heartbeat para verificar conexión
 * - Cola de comandos durante reconexión
 */
class BluetoothConnectionService : Service() {

    companion object {
        private const val TAG = "BTConnectionService"
        private const val CHANNEL_ID = "pastillapp_bluetooth_channel"
        private const val NOTIFICATION_ID = 1001
        
        // UUID estándar para Serial Port Profile (SPP)
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // Tiempos de reconexión (backoff exponencial)
        private const val INITIAL_RECONNECT_DELAY = 1000L // 1 segundo
        private const val MAX_RECONNECT_DELAY = 30000L // 30 segundos
        private const val HEARTBEAT_INTERVAL = 5000L // 5 segundos
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
    
    private var connectionJob: Job? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    
    private val commandQueue = ConcurrentLinkedQueue<String>()
    private val listeners = mutableListOf<BluetoothConnectionListener>()
    
    // Estado actual del dispositivo ESP32
    private var currentDeviceStatus: String = "DESCONOCIDO"
    private var currentLdrValue: Int = 0

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothConnectionService = this@BluetoothConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Servicio creado")
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
     */
    fun sendCommand(command: String) {
        if (isConnected) {
            serviceScope.launch {
                try {
                    val commandBytes = "$command\n".toByteArray()
                    outputStream?.write(commandBytes)
                    outputStream?.flush()
                    Log.d(TAG, "Comando enviado: $command")
                } catch (e: IOException) {
                    Log.e(TAG, "Error enviando comando", e)
                    handleConnectionLost()
                }
            }
        } else {
            // Encolar comando para cuando se reconecte
            commandQueue.offer(command)
            Log.d(TAG, "Comando encolado: $command")
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
                    Log.d(TAG, "Intentando conectar a ${device?.name}")
                    
                    if (!hasBluetoothPermission()) {
                        Log.e(TAG, "Sin permisos de Bluetooth")
                        delay(reconnectDelay)
                        continue
                    }
                    
                    socket = device?.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                    
                    inputStream = socket?.inputStream
                    outputStream = socket?.outputStream
                    
                    isConnected = true
                    reconnectDelay = INITIAL_RECONNECT_DELAY
                    
                    Log.d(TAG, "Conexión exitosa")
                    updateNotification("Conectado a ${getDeviceName()}")
                    notifyConnectionState(true)
                    
                    // Procesar comandos encolados
                    processQueuedCommands()
                    
                    // Iniciar lectura continua
                    startReading()
                    
                    // Iniciar heartbeat
                    startHeartbeat()
                    
                } catch (e: IOException) {
                    Log.e(TAG, "Error de conexión, reintentando en ${reconnectDelay}ms", e)
                    closeConnection()
                    
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Error de permisos", e)
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
                delay(HEARTBEAT_INTERVAL)
                if (isConnected) {
                    try {
                        outputStream?.write("PING\n".toByteArray())
                        outputStream?.flush()
                        Log.d(TAG, "Heartbeat enviado")
                    } catch (e: IOException) {
                        Log.e(TAG, "Error en heartbeat", e)
                        handleConnectionLost()
                        break
                    }
                }
            }
        }
    }

    private fun processReceivedData(data: String) {
        Log.d(TAG, "Datos recibidos: $data")
        
        when {
            // Eventos
            data.startsWith("EVENT:") -> {
                val event = data.substringAfter("EVENT:")
                notifyEvent(event)
            }
            
            // Estado del dispositivo
            data.startsWith("STATUS:") -> {
                currentDeviceStatus = data.substringAfter("STATUS:")
                notifyDeviceStatus(currentDeviceStatus)
            }
            
            // Valor LDR
            data.startsWith("LDR=") -> {
                val ldrStr = data.substringAfter("LDR=")
                currentLdrValue = ldrStr.toIntOrNull() ?: 0
                notifyLdrValue(currentLdrValue)
            }
            
            // UUID
            data.startsWith("UUID:") -> {
                val uuid = data.substringAfter("UUID:")
                notifyDataReceived("UUID", uuid)
            }
            
            // PONG (heartbeat response)
            data == "PONG" -> {
                Log.d(TAG, "Heartbeat OK")
            }
            
            // Otros mensajes
            else -> {
                notifyDataReceived("MESSAGE", data)
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
        Log.d(TAG, "Conexión perdida")
        closeConnection()
        
        if (shouldReconnect) {
            updateNotification("Reconectando...")
            notifyConnectionState(false)
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

