package com.pokkzdev.pastillapp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database for local storage of pill events
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
        @Volatile
        private var INSTANCE: PillEventDatabase? = null

        fun getDatabase(context: Context): PillEventDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PillEventDatabase::class.java,
                    "pill_event_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}


