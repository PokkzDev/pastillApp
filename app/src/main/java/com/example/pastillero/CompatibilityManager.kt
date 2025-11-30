package com.pokkzdev.pastillapp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Gestor de compatibilidad con diferentes versiones de Android
 * Cumple con 3.1.4.14: Asegura la compatibilidad de la aplicación con diferentes versiones de Android
 */
object CompatibilityManager {
    
    private const val TAG = "CompatibilityManager"
    
    // Versiones mínimas soportadas
    const val MIN_SDK = 24 // Android 7.0 Nougat
    const val TARGET_SDK = 36 // Android 14
    
    /**
     * Verifica si la versión actual de Android es compatible
     */
    fun isCompatibleVersion(): Boolean {
        val currentSdk = Build.VERSION.SDK_INT
        val isCompatible = currentSdk >= MIN_SDK
        
        Log.d(TAG, "Android SDK: $currentSdk (Min: $MIN_SDK) - ${if (isCompatible) "Compatible" else "No compatible"}")
        
        return isCompatible
    }
    
    /**
     * Obtiene información detallada de la versión de Android
     */
    fun getAndroidVersionInfo(): AndroidVersionInfo {
        return AndroidVersionInfo(
            sdkVersion = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            codename = Build.VERSION.CODENAME,
            versionName = getVersionName(),
            isCompatible = isCompatibleVersion()
        )
    }
    
    /**
     * Verifica si una característica específica está disponible según la versión
     */
    fun isFeatureAvailable(feature: AndroidFeature): Boolean {
        val currentSdk = Build.VERSION.SDK_INT
        
        return when (feature) {
            AndroidFeature.BLUETOOTH_SCAN -> currentSdk >= Build.VERSION_CODES.S // API 31
            AndroidFeature.BLUETOOTH_CONNECT -> currentSdk >= Build.VERSION_CODES.S // API 31
            AndroidFeature.NOTIFICATION_PERMISSION -> currentSdk >= Build.VERSION_CODES.TIRAMISU // API 33
            AndroidFeature.BLUETOOTH_CLASSIC -> currentSdk >= MIN_SDK // API 24
            AndroidFeature.FOREGROUND_SERVICE -> currentSdk >= Build.VERSION_CODES.O // API 26
            AndroidFeature.NOTIFICATION_CHANNEL -> currentSdk >= Build.VERSION_CODES.O // API 26
            AndroidFeature.ADAPTIVE_ICONS -> currentSdk >= Build.VERSION_CODES.O // API 26
            AndroidFeature.PICTURE_IN_PICTURE -> currentSdk >= Build.VERSION_CODES.O // API 26
        }
    }
    
    /**
     * Obtiene los permisos requeridos según la versión de Android
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        val currentSdk = Build.VERSION.SDK_INT
        
        // Permisos Bluetooth según versión
        if (currentSdk >= Build.VERSION_CODES.S) {
            // Android 12+
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 y anteriores
            permissions.add(android.Manifest.permission.BLUETOOTH)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        // Permisos de ubicación para escaneo Bluetooth
        if (currentSdk >= Build.VERSION_CODES.Q) {
            // Android 10+
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        // Notificaciones (Android 13+)
        if (currentSdk >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return permissions
    }
    
    /**
     * Verifica si todos los permisos necesarios están otorgados
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        val requiredPermissions = getRequiredPermissions()
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Obtiene los permisos faltantes
     */
    fun getMissingPermissions(context: Context): List<String> {
        val requiredPermissions = getRequiredPermissions()
        return requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Verifica compatibilidad de arquitectura de hardware
     */
    fun getHardwareInfo(): HardwareInfo {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        
        return HardwareInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            supportedAbis = supportedAbis,
            primaryAbi = primaryAbi,
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        )
    }
    
    /**
     * Verifica si el dispositivo tiene Bluetooth
     */
    fun hasBluetoothSupport(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
    }
    
    /**
     * Verifica si el dispositivo tiene Bluetooth LE
     */
    fun hasBluetoothLESupport(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    
    /**
     * Genera un reporte completo de compatibilidad
     */
    fun generateCompatibilityReport(context: Context): CompatibilityReport {
        val versionInfo = getAndroidVersionInfo()
        val hardwareInfo = getHardwareInfo()
        val missingPermissions = getMissingPermissions(context)
        val features = AndroidFeature.values().associateWith { isFeatureAvailable(it) }
        
        val issues = mutableListOf<String>()
        
        // Verificar problemas de compatibilidad
        if (!isCompatibleVersion()) {
            issues.add("Versión de Android no soportada (mínimo: API $MIN_SDK)")
        }
        
        if (!hasBluetoothSupport(context)) {
            issues.add("Dispositivo sin soporte Bluetooth")
        }
        
        if (missingPermissions.isNotEmpty()) {
            issues.add("Faltan ${missingPermissions.size} permisos necesarios")
        }
        
        return CompatibilityReport(
            versionInfo = versionInfo,
            hardwareInfo = hardwareInfo,
            hasBluetoothSupport = hasBluetoothSupport(context),
            hasBluetoothLESupport = hasBluetoothLESupport(context),
            availableFeatures = features,
            missingPermissions = missingPermissions,
            issues = issues,
            isFullyCompatible = issues.isEmpty()
        )
    }
    
    private fun getVersionName(): String {
        return when (Build.VERSION.SDK_INT) {
            Build.VERSION_CODES.N -> "7.0 Nougat"
            Build.VERSION_CODES.N_MR1 -> "7.1 Nougat"
            Build.VERSION_CODES.O -> "8.0 Oreo"
            Build.VERSION_CODES.O_MR1 -> "8.1 Oreo"
            Build.VERSION_CODES.P -> "9 Pie"
            Build.VERSION_CODES.Q -> "10"
            Build.VERSION_CODES.R -> "11"
            Build.VERSION_CODES.S -> "12"
            Build.VERSION_CODES.S_V2 -> "12L"
            Build.VERSION_CODES.TIRAMISU -> "13"
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "14"
            else -> if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "14+"
            } else {
                "Unknown"
            }
        }
    }
}

/**
 * Características de Android según versión
 */
enum class AndroidFeature {
    BLUETOOTH_SCAN,
    BLUETOOTH_CONNECT,
    NOTIFICATION_PERMISSION,
    BLUETOOTH_CLASSIC,
    FOREGROUND_SERVICE,
    NOTIFICATION_CHANNEL,
    ADAPTIVE_ICONS,
    PICTURE_IN_PICTURE
}

/**
 * Información de versión de Android
 */
data class AndroidVersionInfo(
    val sdkVersion: Int,
    val release: String,
    val codename: String,
    val versionName: String,
    val isCompatible: Boolean
) {
    override fun toString(): String {
        return "Android $versionName (API $sdkVersion, Release $release)"
    }
}

/**
 * Información de hardware
 */
data class HardwareInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val supportedAbis: List<String>,
    val primaryAbi: String,
    val is64Bit: Boolean
) {
    override fun toString(): String {
        return """
            Fabricante: $manufacturer
            Modelo: $model
            Dispositivo: $device
            ABI: $primaryAbi (${if (is64Bit) "64-bit" else "32-bit"})
            ABIs soportadas: ${supportedAbis.joinToString(", ")}
        """.trimIndent()
    }
}

/**
 * Reporte completo de compatibilidad
 */
data class CompatibilityReport(
    val versionInfo: AndroidVersionInfo,
    val hardwareInfo: HardwareInfo,
    val hasBluetoothSupport: Boolean,
    val hasBluetoothLESupport: Boolean,
    val availableFeatures: Map<AndroidFeature, Boolean>,
    val missingPermissions: List<String>,
    val issues: List<String>,
    val isFullyCompatible: Boolean
) {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("===========================================")
        sb.appendLine("  REPORTE DE COMPATIBILIDAD")
        sb.appendLine("===========================================")
        sb.appendLine()
        sb.appendLine("Sistema:")
        sb.appendLine("  $versionInfo")
        sb.appendLine()
        sb.appendLine("Hardware:")
        sb.appendLine(hardwareInfo.toString().prependIndent("  "))
        sb.appendLine()
        sb.appendLine("Soporte Bluetooth:")
        sb.appendLine("  Bluetooth Clásico: ${if (hasBluetoothSupport) "✓" else "✗"}")
        sb.appendLine("  Bluetooth LE: ${if (hasBluetoothLESupport) "✓" else "✗"}")
        sb.appendLine()
        sb.appendLine("Características disponibles:")
        availableFeatures.forEach { (feature, available) ->
            sb.appendLine("  [${if (available) "✓" else "✗"}] $feature")
        }
        sb.appendLine()
        if (missingPermissions.isNotEmpty()) {
            sb.appendLine("Permisos faltantes:")
            missingPermissions.forEach { permission ->
                sb.appendLine("  • ${permission.substringAfterLast(".")}")
            }
            sb.appendLine()
        }
        if (issues.isNotEmpty()) {
            sb.appendLine("⚠️ Problemas detectados:")
            issues.forEach { issue ->
                sb.appendLine("  • $issue")
            }
        } else {
            sb.appendLine("✓ Dispositivo totalmente compatible")
        }
        sb.appendLine("===========================================")
        return sb.toString()
    }
}
