package com.pokkzdev.pastillapp

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await

object DeviceFirestoreService {
    private const val TAG = "DeviceFirestoreService"
    private val db = FirebaseFirestore.getInstance()
    private const val DEVICES_COLLECTION = "devices"

    /**
     * Registra un nuevo dispositivo en Firestore
     * @param uuid UUID único del dispositivo
     * @param macAddress Dirección MAC Bluetooth del ESP32
     * @param enfermeraUid UID de Firebase Auth de la enfermera que registra el dispositivo
     * @return true si se registró exitosamente, false en caso contrario
     */
    suspend fun registerDevice(
        uuid: String,
        macAddress: String,
        enfermeraUid: String
    ): Boolean {
        return try {
            Log.d(TAG, "Iniciando registro de dispositivo: UUID=$uuid, MAC=$macAddress, UID=$enfermeraUid")
            
            // Validar parámetros
            if (uuid.isBlank()) {
                Log.e(TAG, "UUID está vacío")
                return false
            }
            if (enfermeraUid.isBlank()) {
                Log.e(TAG, "UID de enfermera está vacío")
                return false
            }
            
            // Verificar si el dispositivo ya existe
            Log.d(TAG, "Verificando si el dispositivo ya existe...")
            val deviceDoc = db.collection(DEVICES_COLLECTION).document(uuid).get().await()
            
            if (deviceDoc.exists()) {
                Log.w(TAG, "El dispositivo con UUID $uuid ya existe en Firestore")
                return false
            }

            // Crear el documento del dispositivo
            Log.d(TAG, "Creando documento del dispositivo...")
            val deviceData = hashMapOf(
                "patientId" to "", // Vacío inicialmente, se asignará después
                "enfermeraUid" to enfermeraUid, // Firebase Auth UID
                "macAddress" to macAddress,
                "creadoEn" to FieldValue.serverTimestamp()
            )

            Log.d(TAG, "Guardando datos en Firestore...")
            db.collection(DEVICES_COLLECTION)
                .document(uuid)
                .set(deviceData)
                .await()

            Log.d(TAG, "Dispositivo registrado exitosamente: $uuid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al registrar dispositivo en Firestore", e)
            Log.e(TAG, "Tipo de error: ${e.javaClass.simpleName}")
            Log.e(TAG, "Mensaje de error: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Verifica si un dispositivo ya existe en Firestore
     */
    suspend fun deviceExists(uuid: String): Boolean {
        return try {
            val deviceDoc = db.collection(DEVICES_COLLECTION).document(uuid).get().await()
            deviceDoc.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Error al verificar existencia del dispositivo", e)
            false
        }
    }
}

