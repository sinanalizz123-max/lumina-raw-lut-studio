package com.lumina.studio.core.library

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.albumDataStore by preferencesDataStore(name = "lumina_albums")

/**
 * M13 album persistence: a single JSON document in DataStore.
 * Chosen over Room tables + Migration(3→4) — see [Album] for the rationale.
 * Photos are never duplicated or deleted through this repository.
 */
class AlbumRepository(private val appContext: Context) {

    private object Keys {
        val JSON = stringPreferencesKey("albums_json_v1")
    }

    val store: Flow<AlbumStore> = appContext.albumDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> AlbumJson.decode(prefs[Keys.JSON].orEmpty()) }

    /** Returns the created album, or null when the name is blank / cap hit. */
    suspend fun createAlbum(name: String, nowMs: Long = System.currentTimeMillis()): Album? {
        val clean = AlbumOps.cleanName(name)
        if (clean.isEmpty()) return null
        var created: Album? = null
        appContext.albumDataStore.edit { prefs ->
            val current = AlbumJson.decode(prefs[Keys.JSON].orEmpty())
            if (current.albums.size >= AlbumLimits.MAX_ALBUMS) return@edit
            val album = Album(
                id = "album_" + UUID.randomUUID().toString().take(8),
                name = clean,
                createdAt = nowMs
            )
            created = album
            prefs[Keys.JSON] = AlbumJson.encode(current.copy(albums = current.albums + album))
        }
        return created
    }

    /** Deleting an album keeps every photo; only membership rows go. */
    suspend fun deleteAlbum(albumId: String) {
        appContext.albumDataStore.edit { prefs ->
            val current = AlbumJson.decode(prefs[Keys.JSON].orEmpty())
            val next = AlbumOps.deleteAlbum(current, albumId)
            if (next != current) prefs[Keys.JSON] = AlbumJson.encode(next)
        }
    }

    suspend fun addToAlbum(albumId: String, projectIds: Collection<String>) {
        if (projectIds.isEmpty()) return
        appContext.albumDataStore.edit { prefs ->
            val current = AlbumJson.decode(prefs[Keys.JSON].orEmpty())
            val next = AlbumOps.addEntries(current, albumId, projectIds)
            if (next != current) prefs[Keys.JSON] = AlbumJson.encode(next)
        }
    }

    /** Removing entries never deletes photos — membership only. */
    suspend fun removeFromAlbum(albumId: String, projectIds: Collection<String>) {
        if (projectIds.isEmpty()) return
        appContext.albumDataStore.edit { prefs ->
            val current = AlbumJson.decode(prefs[Keys.JSON].orEmpty())
            val next = AlbumOps.removeEntries(current, albumId, projectIds)
            if (next != current) prefs[Keys.JSON] = AlbumJson.encode(next)
        }
    }

    suspend fun removeProjectFromAll(projectId: String) {
        appContext.albumDataStore.edit { prefs ->
            val current = AlbumJson.decode(prefs[Keys.JSON].orEmpty())
            val next = AlbumOps.removeProjectFromAll(current, projectId)
            if (next != current) prefs[Keys.JSON] = AlbumJson.encode(next)
        }
    }

    suspend fun pruneMissingProjects(existingIds: Set<String>) {
        appContext.albumDataStore.edit { prefs ->
            val current = AlbumJson.decode(prefs[Keys.JSON].orEmpty())
            val next = AlbumOps.pruneMissingProjects(current, existingIds)
            if (next != current) prefs[Keys.JSON] = AlbumJson.encode(next)
        }
    }
}
