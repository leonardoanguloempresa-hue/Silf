package com.silf.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChatEntity::class], version = 1, exportSchema = false)
abstract class SilfDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: SilfDatabase? = null

        fun getInstance(context: Context): SilfDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SilfDatabase::class.java,
                    "silf_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
