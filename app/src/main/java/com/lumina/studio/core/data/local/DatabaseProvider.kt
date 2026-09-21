package com.lumina.studio.core.data.local

import android.content.Context
import androidx.room.Room
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DatabaseProvider {
    @Volatile
    private var instance: LuminaDatabase? = null

    fun get(context: Context): LuminaDatabase {
        return instance ?: synchronized(this) {
            instance ?: openOrRecover(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): LuminaDatabase {
        return Room.databaseBuilder(
            appContext,
            LuminaDatabase::class.java,
            LuminaDatabase.DATABASE_NAME
        ).addMigrations(LuminaMigrations.MIGRATION_1_2, LuminaMigrations.MIGRATION_2_3).build()
    }

    private fun openOrRecover(appContext: Context): LuminaDatabase {
        val database = build(appContext)
        return try {
            database.openHelper.writableDatabase
            database
        } catch (_: Exception) {
            runCatching { database.close() }
            runCatching { backupCorruptFiles(appContext) }
            runCatching { deleteDatabaseFiles(appContext) }
            build(appContext)
        }
    }

    private fun databaseFiles(appContext: Context): List<File> {
        val main = appContext.getDatabasePath(LuminaDatabase.DATABASE_NAME)
        return listOf(main, File(main.path + "-shm"), File(main.path + "-wal"))
    }

    private fun backupCorruptFiles(appContext: Context) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backupDir = File(appContext.filesDir, "db-backup/corrupt-$stamp")
        backupDir.mkdirs()
        for (file in databaseFiles(appContext)) {
            if (file.isFile) {
                runCatching { file.copyTo(File(backupDir, file.name), overwrite = true) }
            }
        }
    }

    private fun deleteDatabaseFiles(appContext: Context) {
        for (file in databaseFiles(appContext)) {
            runCatching { if (file.exists()) file.delete() }
        }
    }
}
