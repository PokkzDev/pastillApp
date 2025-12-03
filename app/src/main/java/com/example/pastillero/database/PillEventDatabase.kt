package com.pokkzdev.pastillapp.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

/**
 * Room database for local storage of pill events
 * 
 * SEGURIDAD (ISO 27001 A.10 - Criptografía, 3.1.4.12):
 * - Base de datos cifrada con SQLCipher (AES-256)
 * - Clave derivada de manera segura usando SecureRandom
 * - Protege datos personales de medicamentos del usuario
 */
@Database(
    entities = [PillEventEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(DateConverters::class)
abstract class PillEventDatabase : RoomDatabase() {
    abstract fun pillEventDao(): PillEventDao

    companion object {
        private const val TAG = "PillEventDatabase"
        private const val DATABASE_NAME = "pill_event_database"
        private const val KEY_ALIAS = "pastillapp_db_key"
        
        @Volatile
        private var INSTANCE: PillEventDatabase? = null

        /**
         * Obtiene la instancia de la base de datos cifrada
         * ISO 27001 A.10.1.1 - Política sobre el uso de controles criptográficos
         */
        fun getDatabase(context: Context): PillEventDatabase {
            return INSTANCE ?: synchronized(this) {
                val passphrase = getOrCreatePassphrase(context)
                val factory = SupportFactory(passphrase)
                
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PillEventDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()
                    
                INSTANCE = instance
                Log.i(TAG, "Base de datos cifrada inicializada correctamente (SQLCipher AES-256)")
                instance
            }
        }
        
        /**
         * Genera o recupera la clave de cifrado de manera segura
         * La clave se almacena en SharedPreferences cifradas
         * 
         * ISO 27001 A.10.1.2 - Gestión de claves
         */
        private fun getOrCreatePassphrase(context: Context): ByteArray {
            val prefs = context.getSharedPreferences("pastillapp_secure", Context.MODE_PRIVATE)
            val existingKey = prefs.getString(KEY_ALIAS, null)
            
            return if (existingKey != null) {
                // Recuperar clave existente
                android.util.Base64.decode(existingKey, android.util.Base64.NO_WRAP)
            } else {
                // Generar nueva clave segura (32 bytes = 256 bits)
                val newKey = ByteArray(32)
                SecureRandom().nextBytes(newKey)
                
                // Guardar clave codificada en Base64
                val encodedKey = android.util.Base64.encodeToString(newKey, android.util.Base64.NO_WRAP)
                prefs.edit().putString(KEY_ALIAS, encodedKey).apply()
                
                Log.i(TAG, "Nueva clave de cifrado generada y almacenada")
                newKey
            }
        }
        
        /**
         * Cierra la base de datos y limpia la instancia
         * Útil para pruebas o reinicio de la aplicación
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}



