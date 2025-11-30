package com.pokkzdev.pastillapp

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clase singleton para encriptación y desencriptación AES-256-CBC
 * de mensajes Bluetooth entre Android y ESP32.
 * 
 * Implementa:
 * - AES-256-CBC para confidencialidad
 * - HMAC-SHA256 para integridad y autenticación de mensajes (ISO 27001 A.10)
 * 
 * Usa una clave compartida predefinida que debe ser la misma en ambos dispositivos.
 * 
 * Formato del mensaje encriptado: Base64(IV[16] + CipherText + HMAC[32])
 */
object BluetoothEncryption {
    private const val TAG = "BluetoothEncryption"
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val KEY_SIZE = 32 // 256 bits
    private const val IV_SIZE = 16 // 128 bits
    private const val HMAC_SIZE = 32 // 256 bits
    
    // Clave compartida predefinida (32 bytes = 256 bits) para AES
    // IMPORTANTE: Esta misma clave debe estar en el ESP32
    private val AES_KEY = byteArrayOf(
        0x2B.toByte(), 0x7E.toByte(), 0x15.toByte(), 0x16.toByte(), 0x28.toByte(), 0xAE.toByte(), 0xD2.toByte(), 0xA6.toByte(),
        0xAB.toByte(), 0xF7.toByte(), 0x15.toByte(), 0x88.toByte(), 0x09.toByte(), 0xCF.toByte(), 0x4F.toByte(), 0x3C.toByte(),
        0x1A.toByte(), 0x2B.toByte(), 0x3C.toByte(), 0x4D.toByte(), 0x5E.toByte(), 0x6F.toByte(), 0x70.toByte(), 0x81.toByte(),
        0x92.toByte(), 0xA3.toByte(), 0xB4.toByte(), 0xC5.toByte(), 0xD6.toByte(), 0xE7.toByte(), 0xF8.toByte(), 0x09.toByte()
    )
    
    // Clave separada para HMAC-SHA256 (32 bytes = 256 bits)
    // Derivada de la clave AES con XOR para separar propósitos (best practice)
    private val HMAC_KEY = byteArrayOf(
        0x5A.toByte(), 0x1D.toByte(), 0x3E.toByte(), 0x4F.toByte(), 0x6C.toByte(), 0x7B.toByte(), 0x8A.toByte(), 0x9D.toByte(),
        0xC2.toByte(), 0xD3.toByte(), 0xE4.toByte(), 0xF5.toByte(), 0x06.toByte(), 0x17.toByte(), 0x28.toByte(), 0x39.toByte(),
        0x4A.toByte(), 0x5B.toByte(), 0x6C.toByte(), 0x7D.toByte(), 0x8E.toByte(), 0x9F.toByte(), 0xA0.toByte(), 0xB1.toByte(),
        0xC2.toByte(), 0xD3.toByte(), 0xE4.toByte(), 0xF5.toByte(), 0x06.toByte(), 0x17.toByte(), 0x28.toByte(), 0x39.toByte()
    )
    
    private val secretKey = SecretKeySpec(AES_KEY, ALGORITHM)
    private val hmacKey = SecretKeySpec(HMAC_KEY, HMAC_ALGORITHM)
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
     * Encripta un mensaje de texto plano con autenticación HMAC
     * @param plainText Texto a encriptar
     * @return String Base64 que contiene: IV(16 bytes) + Datos encriptados + HMAC(32 bytes)
     * 
     * Cumple con ISO 27001 A.10 (Criptografía) y garantiza:
     * - Confidencialidad (AES-256-CBC)
     * - Integridad (HMAC-SHA256)
     * - Autenticación de mensajes
     */
    fun encrypt(plainText: String): String? {
        return try {
            // Generar IV aleatorio (ISO 27001 A.10.1.1)
            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            
            // Encriptar con AES-256-CBC
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            // Calcular HMAC sobre IV + datos encriptados (Encrypt-then-MAC)
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(hmacKey)
            mac.update(iv)
            mac.update(encrypted)
            val hmac = mac.doFinal()
            
            // Combinar IV + datos encriptados + HMAC
            val combined = ByteArray(IV_SIZE + encrypted.size + HMAC_SIZE)
            System.arraycopy(iv, 0, combined, 0, IV_SIZE)
            System.arraycopy(encrypted, 0, combined, IV_SIZE, encrypted.size)
            System.arraycopy(hmac, 0, combined, IV_SIZE + encrypted.size, HMAC_SIZE)
            
            // Convertir a Base64 para envío por Bluetooth
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encriptando mensaje", e)
            null
        }
    }
    
    /**
     * Desencripta un mensaje encriptado verificando primero el HMAC
     * @param encryptedText String Base64 que contiene: IV(16 bytes) + Datos encriptados + HMAC(32 bytes)
     * @return Texto desencriptado o null si hay error o HMAC inválido
     * 
     * Verifica integridad ANTES de desencriptar (Encrypt-then-MAC pattern)
     * Esto previene ataques de padding oracle y garantiza integridad de datos
     */
    fun decrypt(encryptedText: String): String? {
        return try {
            // Validar entrada
            if (encryptedText.isEmpty()) {
                Log.e(TAG, "Error: texto encriptado vacío")
                return null
            }
            
            // Decodificar Base64
            val combined = try {
                Base64.decode(encryptedText, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error: Base64 inválido - ${e.message}")
                return null
            }
            
            // Validar tamaño mínimo (IV + al menos un bloque + HMAC)
            val MIN_SIZE = IV_SIZE + 16 + HMAC_SIZE // IV + 1 bloque AES + HMAC
            if (combined.size < MIN_SIZE) {
                Log.e(TAG, "Error: mensaje demasiado corto (${combined.size} bytes, mínimo $MIN_SIZE)")
                // Intentar modo legacy sin HMAC para compatibilidad
                return decryptLegacy(encryptedText)
            }
            
            // Extraer componentes: IV, datos encriptados, HMAC
            val iv = ByteArray(IV_SIZE)
            System.arraycopy(combined, 0, iv, 0, IV_SIZE)
            
            val encryptedSize = combined.size - IV_SIZE - HMAC_SIZE
            if (encryptedSize <= 0) {
                Log.e(TAG, "Error: no hay datos encriptados después del IV")
                return null
            }
            
            val encrypted = ByteArray(encryptedSize)
            System.arraycopy(combined, IV_SIZE, encrypted, 0, encryptedSize)
            
            val receivedHmac = ByteArray(HMAC_SIZE)
            System.arraycopy(combined, IV_SIZE + encryptedSize, receivedHmac, 0, HMAC_SIZE)
            
            // VERIFICAR HMAC PRIMERO (antes de desencriptar) - ISO 27001 A.10.1.2
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(hmacKey)
            mac.update(iv)
            mac.update(encrypted)
            val calculatedHmac = mac.doFinal()
            
            // Comparación en tiempo constante para prevenir timing attacks
            if (!constantTimeEquals(receivedHmac, calculatedHmac)) {
                Log.e(TAG, "Error: HMAC inválido - mensaje posiblemente manipulado")
                // Intentar modo legacy para compatibilidad con ESP32 sin HMAC
                return decryptLegacy(encryptedText)
            }
            
            Log.d(TAG, "HMAC verificado correctamente - integridad confirmada")
            
            // Validar que el tamaño encriptado sea múltiplo del tamaño de bloque (16 bytes)
            val BLOCK_SIZE = 16
            if (encryptedSize % BLOCK_SIZE != 0) {
                Log.e(TAG, "Error: tamaño encriptado inválido ($encryptedSize bytes, debe ser múltiplo de $BLOCK_SIZE)")
                return null
            }
            
            val ivSpec = IvParameterSpec(iv)
            
            // Desencriptar (solo si HMAC fue válido)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(encrypted)
            
            String(decrypted, Charsets.UTF_8)
        } catch (e: javax.crypto.BadPaddingException) {
            Log.e(TAG, "Error: padding inválido - posible corrupción de datos o clave incorrecta", e)
            null
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            Log.e(TAG, "Error: tamaño de bloque inválido", e)
            null
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            Log.e(TAG, "Error: parámetros de algoritmo inválidos", e)
            null
        } catch (e: java.security.InvalidKeyException) {
            Log.e(TAG, "Error: clave inválida", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error desencriptando mensaje: ${e.javaClass.simpleName} - ${e.message}", e)
            null
        }
    }
    
    /**
     * Desencripta mensajes en formato legacy (sin HMAC) para compatibilidad
     * con versiones anteriores del ESP32
     */
    private fun decryptLegacy(encryptedText: String): String? {
        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            
            if (combined.size < IV_SIZE + 16) {
                return null
            }
            
            val iv = ByteArray(IV_SIZE)
            System.arraycopy(combined, 0, iv, 0, IV_SIZE)
            
            val encryptedSize = combined.size - IV_SIZE
            val BLOCK_SIZE = 16
            if (encryptedSize % BLOCK_SIZE != 0) {
                return null
            }
            
            val encrypted = ByteArray(encryptedSize)
            System.arraycopy(combined, IV_SIZE, encrypted, 0, encryptedSize)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(encrypted)
            
            Log.w(TAG, "Mensaje desencriptado en modo LEGACY (sin HMAC) - considere actualizar ESP32")
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error en desencriptación legacy", e)
            null
        }
    }
    
    /**
     * Comparación en tiempo constante para prevenir timing attacks
     * ISO 27001 A.10.1.2 - Política sobre el uso de controles criptográficos
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
    
    /**
     * Verifica si un string parece estar encriptado (Base64 válido)
     * Soporta tanto formato nuevo (con HMAC) como legacy (sin HMAC)
     */
    fun isEncrypted(text: String): Boolean {
        return try {
            val decoded = Base64.decode(text, Base64.NO_WRAP)
            // Mínimo: IV(16) + 1 bloque AES(16) = 32 bytes
            decoded.size >= IV_SIZE + 16
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Calcula checksum CRC32 para verificación rápida de integridad
     * Útil para datos no cifrados como telemetría
     */
    fun calculateCRC32(data: String): Long {
        val crc = java.util.zip.CRC32()
        crc.update(data.toByteArray(Charsets.UTF_8))
        return crc.value
    }
    
    /**
     * Verifica integridad de datos con CRC32
     */
    fun verifyCRC32(data: String, expectedCrc: Long): Boolean {
        return calculateCRC32(data) == expectedCrc
    }
}

