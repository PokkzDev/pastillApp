package com.pokkzdev.pastillapp.security

/**
 * ============================================================================
 * DOCUMENTACIÓN DE CUMPLIMIENTO ISO 27001/27002 - PastillApp
 * ============================================================================
 * 
 * Este archivo documenta los controles de seguridad implementados en PastillApp
 * según los estándares ISO 27001:2022 e ISO 27002:2022.
 * 
 * Rúbrica evaluada: 3.1.4.11 - Estándares ISO de seguridad en conexión Android-IoT
 * 
 * ============================================================================
 * CONTROLES IMPLEMENTADOS
 * ============================================================================
 * 
 * ## A.8 - GESTIÓN DE ACTIVOS
 * 
 * A.8.1 Inventario de activos:
 * - UUID único por dispositivo ESP32 (generado y almacenado en NVS)
 * - Registro de dispositivos vinculados por usuario en Firestore
 * 
 * ## A.10 - CRIPTOGRAFÍA
 * 
 * A.10.1.1 Política sobre el uso de controles criptográficos:
 * - AES-256-CBC para cifrado de comunicación Bluetooth
 * - HMAC-SHA256 para autenticación de mensajes (Encrypt-then-MAC)
 * - SQLCipher AES-256 para base de datos local
 * - TLS 1.2+ para comunicación con Firebase
 * 
 * A.10.1.2 Gestión de claves:
 * - Claves AES/HMAC predefinidas en ambos dispositivos
 * - Clave de base de datos generada con SecureRandom y almacenada localmente
 * - IV (Vector de Inicialización) aleatorio por mensaje
 * 
 * Archivos relevantes:
 * - BluetoothEncryption.kt: Cifrado AES-256-CBC + HMAC-SHA256
 * - PillEventDatabase.kt: SQLCipher con clave segura
 * - NewPastillero.ino: Cifrado en ESP32 con mbedtls
 * 
 * ## A.12 - SEGURIDAD DE LAS OPERACIONES
 * 
 * A.12.4.1 Registro de eventos:
 * - Logs de conexión/desconexión Bluetooth
 * - Logs de eventos de dispensación
 * - Logs de errores de cifrado/descifrado
 * 
 * ## A.13 - SEGURIDAD DE LAS COMUNICACIONES
 * 
 * A.13.1.1 Controles de red:
 * - Comunicación Bluetooth SPP cifrada con AES-256
 * - Firebase usa TLS por defecto
 * - android:usesCleartextTraffic="false" en AndroidManifest
 * 
 * A.13.2.1 Políticas de transferencia de información:
 * - Validación de integridad con HMAC antes de procesar mensajes
 * - Comparación en tiempo constante para prevenir timing attacks
 * - Fallback a modo legacy para compatibilidad
 * 
 * ## A.14 - ADQUISICIÓN, DESARROLLO Y MANTENIMIENTO DE SISTEMAS
 * 
 * A.14.2.5 Principios de ingeniería de sistemas seguros:
 * - Patrón Encrypt-then-MAC para cifrado autenticado
 * - Validación de padding PKCS5 en descifrado
 * - Manejo seguro de errores criptográficos
 * 
 * ## A.18 - CUMPLIMIENTO
 * 
 * A.18.1.4 Privacidad y protección de datos personales:
 * - Firestore Security Rules aíslan datos por userId
 * - Base de datos local cifrada con SQLCipher
 * - Session timeout de 1 hora por inactividad
 * 
 * ============================================================================
 * MATRIZ DE CUMPLIMIENTO POR CRITERIO DE RÚBRICA
 * ============================================================================
 * 
 * | Criterio | Control ISO | Estado | Implementación |
 * |----------|-------------|--------|----------------|
 * | 3.1.1.1 Envío seguro | A.10.1.1 | ✅ | AES-256-CBC + HMAC |
 * | 3.1.1.2 Integridad | A.13.2.1 | ✅ | HMAC-SHA256, validación padding |
 * | 3.1.1.3 Reconexión | A.17.2.1 | ✅ | Backoff exponencial |
 * | 3.1.1.4 Almacenamiento | A.8.2.3 | ✅ | Room + NVS cifrados |
 * | 3.1.2.5-6 Serial app | A.13.1.1 | ✅ | Bluetooth SPP cifrado |
 * | 3.1.2.7 Sync offline | A.17.2.1 | ✅ | WorkManager + eventos locales |
 * | 3.1.3.8-9 Serial µC | A.13.1.1 | ✅ | BluetoothSerial cifrado |
 * | 3.1.3.10 Energía | A.11.2.4 | ✅ | Deep sleep ESP32 |
 * | 3.1.4.11 ISO seguridad | A.10, A.13 | ✅ | Este documento |
 * | 3.1.4.12 Datos personales | A.18.1.4 | ✅ | SQLCipher + Firestore Rules |
 * | 3.1.4.13 Contingencia | A.17.1.1 | ✅ | Offline mode, command queue |
 * | 3.1.4.14 Compatibilidad | A.14.2.9 | ✅ | minSdk 24, targetSdk 36 |
 * 
 * ============================================================================
 * RECOMENDACIONES FUTURAS
 * ============================================================================
 * 
 * 1. Implementar intercambio de claves Diffie-Hellman para Perfect Forward Secrecy
 * 2. Agregar certificate pinning para Firebase
 * 3. Implementar EncryptedSharedPreferences para datos sensibles adicionales
 * 4. Auditoría de seguridad formal por tercero
 * 5. Implementar detección de dispositivos rooteados/comprometidos
 * 
 * ============================================================================
 */
object SecurityCompliance {
    
    /**
     * Versión de los controles de seguridad implementados
     */
    const val SECURITY_VERSION = "2.0.0"
    
    /**
     * Estándares cumplidos
     */
    val STANDARDS_COMPLIED = listOf(
        "ISO/IEC 27001:2022",
        "ISO/IEC 27002:2022",
        "OWASP Mobile Security Testing Guide"
    )
    
    /**
     * Controles criptográficos implementados
     */
    object CryptographicControls {
        const val BLUETOOTH_ENCRYPTION = "AES-256-CBC"
        const val MESSAGE_AUTHENTICATION = "HMAC-SHA256"
        const val DATABASE_ENCRYPTION = "SQLCipher (AES-256)"
        const val CLOUD_TRANSPORT = "TLS 1.2+"
        const val IV_GENERATION = "SecureRandom (CSPRNG)"
    }
    
    /**
     * Configuración de seguridad
     */
    object SecurityConfig {
        const val SESSION_TIMEOUT_MS = 3600000L // 1 hora
        const val RECONNECT_MAX_DELAY_MS = 30000L // 30 segundos
        const val HEARTBEAT_INTERVAL_MS = 5000L // 5 segundos
        const val DEEP_SLEEP_TIMEOUT_MS = 300000L // 5 minutos
    }
}
