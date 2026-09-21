package com.lumina.studio.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object LuminaMigrations {
    val V1_V2_SQL: List<String> = listOf(
        "ALTER TABLE projects ADD COLUMN editParamsJson TEXT"
    )

    val V2_V3_SQL: List<String> = listOf(
        "ALTER TABLE presets ADD COLUMN category TEXT NOT NULL DEFAULT 'Film'",
        "ALTER TABLE presets ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE presets ADD COLUMN defaultIntensity REAL NOT NULL DEFAULT 1",
        "ALTER TABLE presets ADD COLUMN cubeText TEXT"
    )

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            V1_V2_SQL.forEach { database.execSQL(it) }
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            V2_V3_SQL.forEach { database.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
