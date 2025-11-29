package com.pokkzdev.pastillapp

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Servicio de comunicación serial Bluetooth con el ESP32.
 * Permite conectar, enviar comandos y recibir respuestas.
 * 
 * Protocolo soportado:
 * - PING: Heartbeat
 * - STATUS: Estado del dispositivo
 * - GET_UUID: Obtener UUID
 * - PASTILLA: Abrir compartimento
 * - SILENCIAR: Silenciar alarma
 */
class BluetoothSerialService(private val device: BluetoothDevice) {
    companion object {
        private const val TAG = "BluetoothSerialService"
        // UUID estándar para Serial Port Profile (SPP)
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_TIMEOUT_MS = 5000L // 5 segundos
        private const val HEARTBEAT_TIMEOUT_MS = 3000L // 3 segundos para ping
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    
    // Listeners para eventos
    private val connectionListeners = mutableListOf<ConnectionListener>()
    private val dataListeners = mutableListOf<DataListener>()

    /**
     * Interface para escuchar cambios de conexión
     */
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionError(error: String)
    }

    /**
     * Interface para escuchar datos recibidos
     */
    interface DataListener {
        fun onDataReceived(data: String)
        fun onStatusReceived(status: String)
        fun onLdrValueReceived(value: Int)
        fun onEventReceived(event: String)
    }

    /**
     * Agrega un listener de conexión
     */
    fun addConnectionListener(listener: ConnectionListener) {
        if (!connectionListeners.contains(listener)) {
            connectionListeners.add(listener)
        }
    }

    /**
     * Elimina un listener de conexión
     */
    fun removeConnectionListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    /**
     * Agrega un listener de datos
     */
    fun addDataListener(listener: DataListener) {
        if (!dataListeners.contains(listener)) {
            dataListeners.add(listener)
        }
    }

    /**
     * Elimina un listener de datos
     */
    fun removeDataListener(listener: DataListener) {
        dataListeners.remove(listener)
    }

    /**
     * Conecta al dispositivo Bluetooth
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Conectando a ${device.name} (${device.address})")
            
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()
            
            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            
            isConnected = true
            Log.d(TAG, "Conexión exitosa")
            
            // Notificar listeners
            connectionListeners.forEach { it.onConnected() }
            
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error al conectar", e)
            close()
            connectionListeners.forEach { it.onConnectionError(e.message ?: "Error desconocido") }
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Error de seguridad al conectar", e)
            close()
            connectionListeners.forEach { it.onConnectionError("Permisos de Bluetooth denegados") }
            false
        }
    }

    /**
     * Envía un comando al dispositivo
     */
    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || outputStream == null) {
                Log.e(TAG, "No hay conexión activa")
                return@withContext false
            }
            
            Log.d(TAG, "Enviando comando: $command")
            val commandBytes = "$command\n".toByteArray()
            outputStream?.write(commandBytes)
            outputStream?.flush()
            Log.d(TAG, "Comando enviado exitosamente")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error al enviar comando", e)
            handleConnectionLost()
            false
        }
    }

    /**
     * Lee una respuesta del dispositivo con timeout
     * Lee todas las líneas disponibles hasta encontrar un salto de línea o timeout
     */
    suspend fun readResponse(timeoutMs: Long = READ_TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || inputStream == null) {
                Log.e(TAG, "No hay conexión activa")
                return@withContext null
            }

            val startTime = System.currentTimeMillis()
            val buffer = ByteArray(1024)
            val response = StringBuilder()
            var lastReadTime = startTime

            // Leer datos hasta timeout o hasta que no haya más datos disponibles por un tiempo
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val available = inputStream?.available() ?: 0
                if (available > 0) {
                    val bytesRead = inputStream?.read(buffer) ?: 0
                    if (bytesRead > 0) {
                        val data = String(buffer, 0, bytesRead)
                        response.append(data)
                        lastReadTime = System.currentTimeMillis()
                        Log.d(TAG, "Datos recibidos parciales: ${data.trim()}")
                        
                        // Procesar y notificar datos
                        processReceivedData(data.trim())
                    }
                } else {
                    // Si no hay datos disponibles y ya pasó tiempo desde la última lectura, terminar
                    if (response.isNotEmpty() && System.currentTimeMillis() - lastReadTime > 200) {
                        break
                    }
                }
                
                // Pequeña pausa para evitar consumo excesivo de CPU
                Thread.sleep(50)
            }

            val result = response.toString().trim()
            if (result.isNotEmpty()) {
                Log.d(TAG, "Respuesta completa recibida: $result")
                return@withContext result
            } else {
                Log.w(TAG, "Timeout esperando respuesta")
                return@withContext null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error al leer respuesta", e)
            handleConnectionLost()
            null
        }
    }

    /**
     * Envía un comando y espera una respuesta
     */
    suspend fun sendCommandAndWaitResponse(
        command: String,
        timeoutMs: Long = READ_TIMEOUT_MS
    ): String? {
        return if (sendCommand(command)) {
            readResponse(timeoutMs)
        } else {
            null
        }
    }

    /**
     * Envía PING y espera PONG (heartbeat)
     * @return true si recibe PONG, false en caso contrario
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sendCommand("PING")) return@withContext false
            
            val response = readResponse(HEARTBEAT_TIMEOUT_MS)
            val isPong = response?.contains("PONG") == true
            Log.d(TAG, "Ping result: $isPong")
            isPong
        } catch (e: Exception) {
            Log.e(TAG, "Error en ping", e)
            false
        }
    }

    /**
     * Solicita el estado actual del dispositivo
     */
    suspend fun getStatus(): String? = withContext(Dispatchers.IO) {
        val response = sendCommandAndWaitResponse("STATUS", 3000)
        response?.let {
            if (it.startsWith("STATUS:")) {
                it.substringAfter("STATUS:")
            } else {
                null
            }
        }
    }

    /**
     * Abre el compartimento
     */
    suspend fun openCompartment(): Boolean = withContext(Dispatchers.IO) {
        val response = sendCommandAndWaitResponse("PASTILLA", 3000)
        response?.contains("OK") == true || response?.contains("ABRIENDO") == true
    }

    /**
     * Silencia la alarma
     */
    suspend fun silenceAlarm(): Boolean = withContext(Dispatchers.IO) {
        val response = sendCommandAndWaitResponse("SILENCIAR", 3000)
        response?.contains("OK") == true || response?.contains("SILENCIADO") == true
    }

    /**
     * Verifica si está conectado
     */
    fun isConnected(): Boolean {
        return isConnected && socket?.isConnected == true
    }

    /**
     * Cierra la conexión
     */
    fun close() {
        val wasConnected = isConnected
        try {
            isConnected = false
            inputStream?.close()
            outputStream?.close()
            socket?.close()
            Log.d(TAG, "Conexión cerrada")
            
            if (wasConnected) {
                connectionListeners.forEach { it.onDisconnected() }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error al cerrar conexión", e)
        }
    }

    /**
     * Procesa datos recibidos y notifica a los listeners
     */
    private fun processReceivedData(data: String) {
        // Dividir por líneas en caso de múltiples mensajes
        data.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEach
            
            when {
                trimmedLine.startsWith("STATUS:") -> {
                    val status = trimmedLine.substringAfter("STATUS:")
                    dataListeners.forEach { it.onStatusReceived(status) }
                }
                trimmedLine.startsWith("LDR=") -> {
                    val value = trimmedLine.substringAfter("LDR=").toIntOrNull() ?: 0
                    dataListeners.forEach { it.onLdrValueReceived(value) }
                }
                trimmedLine.startsWith("EVENT:") -> {
                    val event = trimmedLine.substringAfter("EVENT:")
                    dataListeners.forEach { it.onEventReceived(event) }
                }
                else -> {
                    dataListeners.forEach { it.onDataReceived(trimmedLine) }
                }
            }
        }
    }

    /**
     * Maneja la pérdida de conexión
     */
    private fun handleConnectionLost() {
        if (isConnected) {
            isConnected = false
            connectionListeners.forEach { it.onDisconnected() }
            connectionListeners.forEach { it.onConnectionError("Conexión perdida") }
        }
    }
}
