package com.lumina.studio.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String,
    val name: String,
    val photoUri: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val fileType: String = "",
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val presetName: String? = null,
    val isFavorite: Boolean = false,
    val editParamsJson: String? = null
)

@Entity(tableName = "edit_history")
data class EditHistory(
    @PrimaryKey val id: String,
    val projectId: String,
    val toolName: String,
    val createdAt: Long = 0L
)

@Entity(tableName = "presets")
data class Preset(
    @PrimaryKey val id: String,
    val packId: String? = null,
    val name: String,
    // Phase 2B: the Preset/Pack tables are reused for the LUT library. Built-in
    // demo presets are seeded rows with cubeText == null (their procedural 3D
    // LUT is resolved in code from BuiltInPresets by id); imported .CUBE files
    // store their raw text in cubeText and are parsed on demand via CubeParser.
    val category: String = "Film",
    val isFavorite: Boolean = false,
    val defaultIntensity: Float = 1f,
    val cubeText: String? = null,
    val createdAt: Long = 0L
)

@Entity(tableName = "packs")
data class Pack(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long = 0L
)
