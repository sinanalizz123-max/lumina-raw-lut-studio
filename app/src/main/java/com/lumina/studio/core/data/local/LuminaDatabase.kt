package com.lumina.studio.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Project::class, EditHistory::class, Preset::class, Pack::class],
    version = 3, // version = 1 -> 2: added Project.editParamsJson; 2 -> 3: added Preset.category/isFavorite/defaultIntensity/cubeText (destructive fallback)
    exportSchema = false
)
abstract class LuminaDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun editHistoryDao(): EditHistoryDao
    abstract fun presetDao(): PresetDao
    abstract fun packDao(): PackDao

    companion object {
        const val DATABASE_NAME = "lumina.db"
    }
}
