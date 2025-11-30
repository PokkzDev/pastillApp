package com.pokkzdev.pastillapp

import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * Gestor de optimización de consumo energético
 * Cumple con 3.1.3.10: Evalúa el consumo de energía y optimiza la duración de la batería
 */
class PowerOptimizationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "PowerOptimization"
        private const val WAKELOCK_TAG = "PastillApp:BluetoothWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 60000L // 1 minuto
        
        // Modos de escaneo según nivel de batería
        private const val HIGH_BATTERY_THRESHOLD = 50 // %
        private const val LOW_BATTERY_THRESHOLD = 20 // %
    }
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitoringJob: Job? = null
    
    /**
     * Obtiene el modo de escaneo óptimo según el nivel de batería
     */
    fun getOptimalScanMode(): Int {
        val batteryLevel = getBatteryLevel()
        
        return when {
            batteryLevel > HIGH_BATTERY_THRESHOLD -> {
                Log.d(TAG, "Batería alta ($batteryLevel%), usando escaneo de baja latencia")
                ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            batteryLevel > LOW_BATTERY_THRESHOLD -> {
                Log.d(TAG, "Batería media ($batteryLevel%), usando escaneo balanceado")
                ScanSettings.SCAN_MODE_BALANCED
            }
            else -> {
                Log.d(TAG, "Batería baja ($batteryLevel%), usando escaneo de bajo consumo")
                ScanSettings.SCAN_MODE_LOW_POWER
            }
        }
    }
    
    /**
     * Obtiene el nivel de batería actual
     * @return Nivel de batería (0-100)
     */
    fun getBatteryLevel(): Int {
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    /**
     * Verifica si el dispositivo está en modo de ahorro de energía
     */
    fun isInPowerSaveMode(): Boolean {
        return powerManager.isPowerSaveMode
    }
    
    /**
     * Verifica si el dispositivo está cargando
     */
    fun isCharging(): Boolean {
        return batteryManager.isCharging
    }
    
    /**
     * Obtiene el estado de carga de la batería
     */
    fun getBatteryStatus(): String {
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
            BatteryManager.BATTERY_STATUS_FULL -> "Completa"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "No cargando"
            else -> "Desconocido"
        }
    }
    
    /**
     * Adquiere un wake lock parcial para mantener el CPU activo durante operaciones Bluetooth críticas
     */
    fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
        }
        
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(WAKELOCK_TIMEOUT_MS)
                Log.d(TAG, "Wake lock adquirido")
            }
        }
    }
    
    /**
     * Libera el wake lock
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock liberado")
            }
        }
    }
    
    /**
     * Obtiene el intervalo de heartbeat óptimo según el nivel de batería
     * @return Intervalo en milisegundos
     */
    fun getOptimalHeartbeatInterval(): Long {
        val batteryLevel = getBatteryLevel()
        
        return when {
            isCharging() -> 3000L // 3 segundos si está cargando
            batteryLevel > HIGH_BATTERY_THRESHOLD -> 5000L // 5 segundos
            batteryLevel > LOW_BATTERY_THRESHOLD -> 10000L // 10 segundos
            else -> 20000L // 20 segundos
        }
    }
    
    /**
     * Obtiene el intervalo de telemetría óptimo según el nivel de batería
     */
    fun getOptimalTelemetryInterval(): Long {
        val batteryLevel = getBatteryLevel()
        
        return when {
            isCharging() -> 1000L // 1 segundo si está cargando
            batteryLevel > HIGH_BATTERY_THRESHOLD -> 2000L // 2 segundos
            batteryLevel > LOW_BATTERY_THRESHOLD -> 5000L // 5 segundos
            else -> 10000L // 10 segundos
        }
    }
    
    /**
     * Recomienda si se debe reducir la frecuencia de telemetría
     */
    fun shouldReduceTelemetryFrequency(): Boolean {
        return getBatteryLevel() < LOW_BATTERY_THRESHOLD || isInPowerSaveMode()
    }
    
    /**
     * Recomienda si se debe pausar operaciones no críticas
     */
    fun shouldPauseNonCriticalOperations(): Boolean {
        val batteryLevel = getBatteryLevel()
        return batteryLevel < 10 || (isInPowerSaveMode() && batteryLevel < 20)
    }
    
    /**
     * Inicia monitoreo continuo del nivel de batería
     */
    fun startBatteryMonitoring(scope: CoroutineScope, onBatteryLevelChanged: (Int) -> Unit) {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            var lastLevel = getBatteryLevel()
            var lastChargingState = isCharging()
            
            while (isActive) {
                delay(30000) // Verificar cada 30 segundos
                
                val currentLevel = getBatteryLevel()
                val currentChargingState = isCharging()
                
                if (currentLevel != lastLevel) {
                    Log.d(TAG, "Nivel de batería cambió: $lastLevel% -> $currentLevel%")
                    onBatteryLevelChanged(currentLevel)
                    lastLevel = currentLevel
                    
                    // Alertas
                    when {
                        currentLevel < 15 -> {
                            Log.w(TAG, "⚠️ Batería crítica: $currentLevel%")
                        }
                        currentLevel < 30 && lastLevel >= 30 -> {
                            Log.w(TAG, "⚠️ Batería baja: $currentLevel%")
                        }
                    }
                }
                
                if (currentChargingState != lastChargingState) {
                    Log.d(TAG, "Estado de carga cambió: ${if (currentChargingState) "Cargando" else "Descargando"}")
                    lastChargingState = currentChargingState
                    onBatteryLevelChanged(currentLevel) // Notificar cambio de estado
                }
            }
        }
        Log.d(TAG, "Monitoreo de batería iniciado")
    }
    
    /**
     * Detiene el monitoreo de batería
     */
    fun stopBatteryMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.d(TAG, "Monitoreo de batería detenido")
    }
    
    /**
     * Libera todos los recursos
     */
    fun cleanup() {
        stopBatteryMonitoring()
        releaseWakeLock()
    }
    
    /**
     * Genera un reporte de consumo energético
     */
    fun generatePowerReport(): PowerReport {
        return PowerReport(
            batteryLevel = getBatteryLevel(),
            batteryStatus = getBatteryStatus(),
            isCharging = isCharging(),
            isPowerSaveMode = isInPowerSaveMode(),
            recommendedScanMode = getOptimalScanMode(),
            recommendedHeartbeatInterval = getOptimalHeartbeatInterval(),
            recommendedTelemetryInterval = getOptimalTelemetryInterval(),
            shouldReduceTelemetry = shouldReduceTelemetryFrequency(),
            shouldPauseNonCritical = shouldPauseNonCriticalOperations()
        )
    }
    
    /**
     * Obtiene recomendaciones de optimización
     */
    fun getOptimizationRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val batteryLevel = getBatteryLevel()
        
        when {
            batteryLevel < 15 -> {
                recommendations.add("🔋 Batería crítica: Conectar cargador inmediatamente")
                recommendations.add("⚠️ Las operaciones se limitarán al mínimo necesario")
            }
            batteryLevel < 30 -> {
                recommendations.add("🔋 Batería baja: Se recomienda cargar pronto")
                recommendations.add("⚡ Reduciendo frecuencia de escaneo y telemetría")
            }
            batteryLevel < 50 -> {
                recommendations.add("🔋 Batería media: Operación normal con optimizaciones")
            }
        }
        
        if (isInPowerSaveMode()) {
            recommendations.add("💡 Modo ahorro de energía activo")
            recommendations.add("⚡ Reduciendo operaciones en segundo plano")
        }
        
        if (isCharging()) {
            recommendations.add("🔌 Dispositivo cargando: Operación a máximo rendimiento")
        }
        
        return recommendations
    }
}

/**
 * Reporte de estado energético
 */
data class PowerReport(
    val batteryLevel: Int,
    val batteryStatus: String,
    val isCharging: Boolean,
    val isPowerSaveMode: Boolean,
    val recommendedScanMode: Int,
    val recommendedHeartbeatInterval: Long,
    val recommendedTelemetryInterval: Long,
    val shouldReduceTelemetry: Boolean,
    val shouldPauseNonCritical: Boolean
) {
    override fun toString(): String {
        return """
            |=== Reporte de Energía ===
            |Batería: $batteryLevel% ($batteryStatus)
            |Cargando: ${if (isCharging) "Sí" else "No"}
            |Modo ahorro: ${if (isPowerSaveMode) "Activo" else "Inactivo"}
            |Intervalo heartbeat: ${recommendedHeartbeatInterval}ms
            |Intervalo telemetría: ${recommendedTelemetryInterval}ms
            |Reducir telemetría: ${if (shouldReduceTelemetry) "Sí" else "No"}
            |Pausar no crítico: ${if (shouldPauseNonCritical) "Sí" else "No"}
        """.trimMargin()
    }
}
