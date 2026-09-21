package com.lumina.studio.core.data.local

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile
    private var instance: LuminaDatabase? = null

    fun get(context: Context): LuminaDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LuminaDatabase::class.java,
                LuminaDatabase.DATABASE_NAME
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
