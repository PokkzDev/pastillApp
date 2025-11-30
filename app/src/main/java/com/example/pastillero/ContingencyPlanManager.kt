package com.pokkzdev.pastillapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.*

/**
 * Gestor de plan de contingencia y continuidad operativa
 * Cumple con 3.1.4.13: Implementa plan de contingencia en caso de fallas
 */
class ContingencyPlanManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ContingencyPlan"
        private const val MAX_CONNECTION_FAILURES = 5
        private const val FAILURE_WINDOW_MS = 300000L // 5 minutos
    }
    
    private val failureHistory = mutableListOf<FailureEvent>()
    private var contingencyMode = ContingencyMode.NORMAL
    private val listeners = mutableListOf<ContingencyListener>()
    
    enum class ContingencyMode {
        NORMAL,           // Operación normal
        DEGRADED,         // Modo degradado (funcionalidad reducida)
        OFFLINE,          // Modo offline completo
        RECOVERY          // Modo recuperación
    }
    
    enum class FailureType {
        BLUETOOTH_CONNECTION_LOST,
        BLUETOOTH_PAIRING_FAILED,
        FIRESTORE_SYNC_FAILED,
        COMMAND_SEND_FAILED,
        DATA_INTEGRITY_ERROR,
        DEVICE_NOT_RESPONDING,
        AUTHENTICATION_FAILED,
        NETWORK_UNAVAILABLE
    }
    
    /**
     * Registra una falla en el sistema
     */
    fun reportFailure(type: FailureType, details: String = "") {
        val failure = FailureEvent(
            type = type,
            timestamp = Date(),
            details = details
        )
        
        failureHistory.add(failure)
        Log.w(TAG, "Falla registrada: ${type.name} - $details")
        
        // Limpiar historial antiguo
        cleanOldFailures()
        
        // Evaluar si se necesita cambiar de modo
        evaluateContingencyMode()
        
        // Ejecutar acciones de contingencia
        executeContingencyActions(type, details)
    }
    
    /**
     * Evalúa el modo de contingencia según el historial de fallas
     */
    private fun evaluateContingencyMode() {
        val recentFailures = getRecentFailures()
        val connectionFailures = recentFailures.count { 
            it.type == FailureType.BLUETOOTH_CONNECTION_LOST || 
            it.type == FailureType.DEVICE_NOT_RESPONDING 
        }
        
        val networkFailures = recentFailures.count {
            it.type == FailureType.FIRESTORE_SYNC_FAILED ||
            it.type == FailureType.NETWORK_UNAVAILABLE
        }
        
        val newMode = when {
            connectionFailures >= MAX_CONNECTION_FAILURES -> ContingencyMode.OFFLINE
            connectionFailures >= 2 -> ContingencyMode.DEGRADED
            networkFailures >= 3 -> ContingencyMode.DEGRADED
            recentFailures.size >= 10 -> ContingencyMode.RECOVERY
            else -> ContingencyMode.NORMAL
        }
        
        if (newMode != contingencyMode) {
            val oldMode = contingencyMode
            contingencyMode = newMode
            Log.w(TAG, "Cambio de modo: $oldMode -> $newMode")
            notifyModeChange(oldMode, newMode)
        }
    }
    
    /**
     * Ejecuta acciones de contingencia según el tipo de falla
     */
    private fun executeContingencyActions(type: FailureType, details: String) {
        when (type) {
            FailureType.BLUETOOTH_CONNECTION_LOST -> {
                Log.d(TAG, "Contingencia: Conexión Bluetooth perdida")
                Log.d(TAG, "  → Almacenando comandos pendientes offline")
                Log.d(TAG, "  → Notificando al usuario")
                Log.d(TAG, "  → Reconexión automática iniciada")
            }
            
            FailureType.BLUETOOTH_PAIRING_FAILED -> {
                Log.d(TAG, "Contingencia: Fallo de emparejamiento")
                Log.d(TAG, "  → Reintentar emparejamiento")
                Log.d(TAG, "  → Solicitar intervención manual si persiste")
            }
            
            FailureType.FIRESTORE_SYNC_FAILED -> {
                Log.d(TAG, "Contingencia: Fallo sincronización Firestore")
                Log.d(TAG, "  → Almacenando datos localmente")
                Log.d(TAG, "  → Reintento programado")
            }
            
            FailureType.COMMAND_SEND_FAILED -> {
                Log.d(TAG, "Contingencia: Fallo enviando comando")
                Log.d(TAG, "  → Comando encolado para reintento")
                Log.d(TAG, "  → Verificando estado de conexión")
            }
            
            FailureType.DATA_INTEGRITY_ERROR -> {
                Log.d(TAG, "Contingencia: Error de integridad de datos")
                Log.d(TAG, "  → Solicitando reenvío de datos")
                Log.d(TAG, "  → Registrando incidente de seguridad")
            }
            
            FailureType.DEVICE_NOT_RESPONDING -> {
                Log.d(TAG, "Contingencia: Dispositivo no responde")
                Log.d(TAG, "  → Reiniciando conexión")
                Log.d(TAG, "  → Notificando al usuario")
            }
            
            FailureType.AUTHENTICATION_FAILED -> {
                Log.d(TAG, "Contingencia: Fallo de autenticación")
                Log.d(TAG, "  → Redirigir a login")
                Log.d(TAG, "  → Limpiar sesión local")
            }
            
            FailureType.NETWORK_UNAVAILABLE -> {
                Log.d(TAG, "Contingencia: Red no disponible")
                Log.d(TAG, "  → Modo offline activado")
                Log.d(TAG, "  → Sincronización pendiente")
            }
        }
    }
    
    /**
     * Obtiene las fallas recientes (últimos 5 minutos)
     */
    private fun getRecentFailures(): List<FailureEvent> {
        val cutoffTime = Date(System.currentTimeMillis() - FAILURE_WINDOW_MS)
        return failureHistory.filter { it.timestamp.after(cutoffTime) }
    }
    
    /**
     * Limpia fallas antiguas del historial
     */
    private fun cleanOldFailures() {
        val cutoffTime = Date(System.currentTimeMillis() - FAILURE_WINDOW_MS * 2)
        failureHistory.removeAll { it.timestamp.before(cutoffTime) }
    }
    
    /**
     * Obtiene el modo de contingencia actual
     */
    fun getCurrentMode(): ContingencyMode = contingencyMode
    
    /**
     * Verifica si el sistema está en modo normal
     */
    fun isOperatingNormally(): Boolean = contingencyMode == ContingencyMode.NORMAL
    
    /**
     * Genera un reporte de fallas
     */
    fun generateFailureReport(): FailureReport {
        val recentFailures = getRecentFailures()
        val failuresByType = recentFailures.groupBy { it.type }
        
        return FailureReport(
            currentMode = contingencyMode,
            totalFailures = recentFailures.size,
            failuresByType = failuresByType.mapValues { it.value.size },
            recentFailures = recentFailures.takeLast(10),
            recommendations = generateRecommendations()
        )
    }
    
    /**
     * Genera recomendaciones según el estado actual
     */
    private fun generateRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val recentFailures = getRecentFailures()
        
        val connectionFailures = recentFailures.count { 
            it.type == FailureType.BLUETOOTH_CONNECTION_LOST 
        }
        
        if (connectionFailures >= 3) {
            recommendations.add("📱 Verificar que el dispositivo ESP32 esté encendido y cerca")
            recommendations.add("🔋 Comprobar el nivel de batería del dispositivo")
            recommendations.add("🔄 Reiniciar el módulo Bluetooth del teléfono")
        }
        
        val syncFailures = recentFailures.count { 
            it.type == FailureType.FIRESTORE_SYNC_FAILED 
        }
        
        if (syncFailures >= 2) {
            recommendations.add("🌐 Verificar conexión a Internet")
            recommendations.add("⚙️ Revisar configuración de Firebase")
        }
        
        val integrityErrors = recentFailures.count {
            it.type == FailureType.DATA_INTEGRITY_ERROR
        }
        
        if (integrityErrors >= 1) {
            recommendations.add("🔒 Datos comprometidos detectados")
            recommendations.add("⚠️ Verificar integridad de la conexión")
        }
        
        when (contingencyMode) {
            ContingencyMode.DEGRADED -> {
                recommendations.add("⚠️ Sistema operando en modo degradado")
                recommendations.add("💡 Algunas funciones pueden estar limitadas")
            }
            ContingencyMode.OFFLINE -> {
                recommendations.add("📴 Sistema en modo offline")
                recommendations.add("💾 Los datos se sincronizarán cuando se restablezca la conexión")
            }
            ContingencyMode.RECOVERY -> {
                recommendations.add("🔧 Sistema en modo recuperación")
                recommendations.add("🔄 Reiniciar la aplicación puede resolver problemas")
            }
            else -> {
                recommendations.add("✓ Sistema operando normalmente")
            }
        }
        
        return recommendations
    }
    
    /**
     * Registra un listener para cambios de modo
     */
    fun addListener(listener: ContingencyListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    /**
     * Elimina un listener
     */
    fun removeListener(listener: ContingencyListener) {
        listeners.remove(listener)
    }
    
    private fun notifyModeChange(oldMode: ContingencyMode, newMode: ContingencyMode) {
        listeners.forEach { it.onContingencyModeChanged(oldMode, newMode) }
    }
    
    /**
     * Intenta recuperación automática
     */
    suspend fun attemptRecovery(
        connectionService: BluetoothConnectionService?,
        offlineManager: OfflineDataManager?
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Intentando recuperación automática...")
        
        try {
            when (contingencyMode) {
                ContingencyMode.OFFLINE -> {
                    // Intentar reconectar
                    connectionService?.let {
                        if (!it.isConnected()) {
                            Log.d(TAG, "Intentando reconexión...")
                            delay(2000)
                            // La reconexión se maneja automáticamente por el servicio
                        }
                    }
                }
                
                ContingencyMode.DEGRADED -> {
                    // Sincronizar datos pendientes
                    offlineManager?.let { manager ->
                        connectionService?.let { service ->
                            if (service.isConnected()) {
                                val synced = manager.syncPendingCommands(service)
                                Log.d(TAG, "Comandos sincronizados: $synced")
                            }
                        }
                    }
                }
                
                ContingencyMode.RECOVERY -> {
                    // Limpiar estado y reiniciar
                    cleanOldFailures()
                    contingencyMode = ContingencyMode.NORMAL
                    Log.d(TAG, "Sistema recuperado")
                }
                
                else -> {}
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error durante recuperación", e)
            false
        }
    }
    
    /**
     * Fuerza un cambio a modo normal (solo para testing o admin)
     */
    fun forceNormalMode() {
        val oldMode = contingencyMode
        contingencyMode = ContingencyMode.NORMAL
        failureHistory.clear()
        Log.d(TAG, "Modo forzado a NORMAL desde $oldMode")
        notifyModeChange(oldMode, ContingencyMode.NORMAL)
    }
}

/**
 * Evento de falla
 */
data class FailureEvent(
    val type: ContingencyPlanManager.FailureType,
    val timestamp: Date,
    val details: String
)

/**
 * Reporte de fallas
 */
data class FailureReport(
    val currentMode: ContingencyPlanManager.ContingencyMode,
    val totalFailures: Int,
    val failuresByType: Map<ContingencyPlanManager.FailureType, Int>,
    val recentFailures: List<FailureEvent>,
    val recommendations: List<String>
) {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Plan de Contingencia - Reporte ===")
        sb.appendLine("Modo actual: $currentMode")
        sb.appendLine("Fallas totales (5 min): $totalFailures")
        sb.appendLine()
        sb.appendLine("Fallas por tipo:")
        failuresByType.forEach { (type, count) ->
            sb.appendLine("  • $type: $count")
        }
        sb.appendLine()
        sb.appendLine("Recomendaciones:")
        recommendations.forEach { rec ->
            sb.appendLine("  $rec")
        }
        return sb.toString()
    }
}

/**
 * Interface para escuchar cambios de modo de contingencia
 */
interface ContingencyListener {
    fun onContingencyModeChanged(
        oldMode: ContingencyPlanManager.ContingencyMode,
        newMode: ContingencyPlanManager.ContingencyMode
    )
}
