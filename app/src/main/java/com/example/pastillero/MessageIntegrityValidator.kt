package com.pokkzdev.pastillapp

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Validador de integridad de mensajes usando HMAC-SHA256
 * Cumple con 3.1.1.2: Evalúa si los datos enviados y recibidos mantienen su integridad
 */
object MessageIntegrityValidator {
    private const val TAG = "MessageIntegrity"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    
    // Clave HMAC (32 bytes) - Debe ser la misma en ESP32 y Android
    private val HMAC_KEY = byteArrayOf(
        0x3A.toByte(), 0x8F.toByte(), 0x24.toByte(), 0x17.toByte(), 
        0x39.toByte(), 0xBE.toByte(), 0xE3.toByte(), 0xB7.toByte(),
        0xBA.toByte(), 0xF8.toByte(), 0x26.toByte(), 0x99.toByte(), 
        0x1A.toByte(), 0xDF.toByte(), 0x5F.toByte(), 0x4D.toByte(),
        0x2B.toByte(), 0x3C.toByte(), 0x4D.toByte(), 0x5E.toByte(), 
        0x6F.toByte(), 0x80.toByte(), 0x91.toByte(), 0xA2.toByte(),
        0xB3.toByte(), 0xC4.toByte(), 0xD5.toByte(), 0xE6.toByte(), 
        0xF7.toByte(), 0x08.toByte(), 0x19.toByte(), 0x2A.toByte()
    )
    
    /**
     * Genera un HMAC para un mensaje
     * @param message Mensaje a proteger
     * @return HMAC en formato hexadecimal
     */
    fun generateHMAC(message: String): String? {
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            val secretKeySpec = SecretKeySpec(HMAC_KEY, HMAC_ALGORITHM)
            mac.init(secretKeySpec)
            val hmacBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            bytesToHex(hmacBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error generando HMAC", e)
            null
        }
    }
    
    /**
     * Verifica la integridad de un mensaje
     * @param message Mensaje recibido
     * @param receivedHMAC HMAC recibido
     * @return true si el HMAC es válido
     */
    fun verifyHMAC(message: String, receivedHMAC: String): Boolean {
        val calculatedHMAC = generateHMAC(message) ?: return false
        return calculatedHMAC.equals(receivedHMAC, ignoreCase = true)
    }
    
    /**
     * Añade HMAC a un mensaje: mensaje|HMAC
     * @param message Mensaje original
     * @return Mensaje con HMAC adjunto
     */
    fun attachHMAC(message: String): String? {
        val hmac = generateHMAC(message) ?: return null
        return "$message|$hmac"
    }
    
    /**
     * Verifica y extrae mensaje de formato: mensaje|HMAC
     * @param messageWithHMAC Mensaje con HMAC adjunto
     * @return Mensaje original si es válido, null si no
     */
    fun verifyAndExtract(messageWithHMAC: String): String? {
        val parts = messageWithHMAC.split("|")
        if (parts.size != 2) {
            Log.w(TAG, "Formato inválido: se esperaba mensaje|HMAC")
            return null
        }
        
        val message = parts[0]
        val receivedHMAC = parts[1]
        
        return if (verifyHMAC(message, receivedHMAC)) {
            Log.d(TAG, "Integridad verificada correctamente")
            message
        } else {
            Log.e(TAG, "Integridad comprometida: HMAC no coincide")
            null
        }
    }
    
    /**
     * Genera un checksum SHA-256 simple para un mensaje
     * @param message Mensaje
     * @return Checksum en hexadecimal
     */
    fun generateChecksum(message: String): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(message.toByteArray(Charsets.UTF_8))
            bytesToHex(hashBytes).take(16) // Primeros 16 caracteres
        } catch (e: Exception) {
            Log.e(TAG, "Error generando checksum", e)
            null
        }
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        bytes.forEach {
            val i = it.toInt()
            result.append(hexChars[i shr 4 and 0x0f])
            result.append(hexChars[i and 0x0f])
        }
        return result.toString()
    }
}
