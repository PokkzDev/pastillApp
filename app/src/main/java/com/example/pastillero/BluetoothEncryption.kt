package com.pokkzdev.pastillapp

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clase singleton para encriptación y desencriptación AES-256-CBC
 * de mensajes Bluetooth entre Android y ESP32.
 * 
 * Usa una clave compartida predefinida que debe ser la misma en ambos dispositivos.
 */
object BluetoothEncryption {
    private const val TAG = "BluetoothEncryption"
    private const val ALGORITHM = "AES" 
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_SIZE = 32 // 256 bits
    private const val IV_SIZE = 16 // 128 bits
    
    // Clave compartida predefinida (32 bytes = 256 bits)
    // IMPORTANTE: Esta misma clave debe estar en el ESP32
    private val SHARED_KEY = byteArrayOf(
        0x2B.toByte(), 0x7E.toByte(), 0x15.toByte(), 0x16.toByte(), 0x28.toByte(), 0xAE.toByte(), 0xD2.toByte(), 0xA6.toByte(),
        0xAB.toByte(), 0xF7.toByte(), 0x15.toByte(), 0x88.toByte(), 0x09.toByte(), 0xCF.toByte(), 0x4F.toByte(), 0x3C.toByte(),
        0x1A.toByte(), 0x2B.toByte(), 0x3C.toByte(), 0x4D.toByte(), 0x5E.toByte(), 0x6F.toByte(), 0x70.toByte(), 0x81.toByte(),
        0x92.toByte(), 0xA3.toByte(), 0xB4.toByte(), 0xC5.toByte(), 0xD6.toByte(), 0xE7.toByte(), 0xF8.toByte(), 0x09.toByte()
    )
    
    private val secretKey = SecretKeySpec(SHARED_KEY, ALGORITHM)
    private val secureRandom = SecureRandom()
    
    /**
     * Genera una clave aleatoria de 64 caracteres (Base64)
     * Útil para generar claves compartidas
     */
    fun generateRandomKey(): String {
        val keyBytes = ByteArray(48) // 48 bytes = 64 caracteres en Base64
        secureRandom.nextBytes(keyBytes)
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP)
    }
    
    /**
     * Encripta un mensaje de texto plano
     * @param plainText Texto a encriptar
     * @return String Base64 que contiene: IV(16 bytes) + Datos encriptados
     */
    fun encrypt(plainText: String): String? {
        return try {
            // Generar IV aleatorio
            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            
            // Encriptar
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            // Combinar IV + datos encriptados
            val combined = ByteArray(IV_SIZE + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, IV_SIZE)
            System.arraycopy(encrypted, 0, combined, IV_SIZE, encrypted.size)
            
            // Convertir a Base64 para envío por Bluetooth
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encriptando mensaje", e)
            null
        }
    }
    
    /**
     * Desencripta un mensaje encriptado
     * @param encryptedText String Base64 que contiene: IV(16 bytes) + Datos encriptados
     * @return Texto desencriptado o null si hay error
     */
    fun decrypt(encryptedText: String): String? {
        return try {
            // Decodificar Base64
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            
            if (combined.size < IV_SIZE) {
                Log.e(TAG, "Mensaje encriptado demasiado corto")
                return null
            }
            
            // Extraer IV y datos encriptados
            val iv = ByteArray(IV_SIZE)
            System.arraycopy(combined, 0, iv, 0, IV_SIZE)
            
            val encrypted = ByteArray(combined.size - IV_SIZE)
            System.arraycopy(combined, IV_SIZE, encrypted, 0, encrypted.size)
            
            val ivSpec = IvParameterSpec(iv)
            
            // Desencriptar
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(encrypted)
            
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error desencriptando mensaje", e)
            null
        }
    }
    
    /**
     * Verifica si un string parece estar encriptado (Base64 válido)
     */
    fun isEncrypted(text: String): Boolean {
        return try {
            val decoded = Base64.decode(text, Base64.NO_WRAP)
            decoded.size >= IV_SIZE
        } catch (e: Exception) {
            false
        }
    }
}

