package com.lumina.studio.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Project>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Project>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): Project?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: String): Flow<Project?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: Project)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface EditHistoryDao {
    @Query("SELECT * FROM edit_history WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeForProject(projectId: String): Flow<List<EditHistory>>

    @Query("SELECT COUNT(*) FROM edit_history WHERE projectId = :projectId")
    fun observeCountForProject(projectId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM edit_history WHERE projectId = :projectId")
    suspend fun countForProject(projectId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: EditHistory)

    @Query("DELETE FROM edit_history WHERE projectId = :projectId")
    suspend fun clearForProject(projectId: String)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY name ASC")
    fun observePresets(): Flow<List<Preset>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun getById(id: String): Preset?

    @Query("SELECT * FROM presets WHERE id = :id")
    fun observeById(id: String): Flow<Preset?>

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: Preset)
}

@Dao
interface PackDao {
    @Query("SELECT * FROM packs ORDER BY name ASC")
    fun observePacks(): Flow<List<Pack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pack: Pack)
}
